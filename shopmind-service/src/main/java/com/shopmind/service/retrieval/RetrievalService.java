package com.shopmind.service.retrieval;

import com.shopmind.common.vector.Distance;
import com.shopmind.index.Bm25Index;
import com.shopmind.index.BruteForceIndex;
import com.shopmind.index.HnswIndex;
import com.shopmind.index.HybridRanker;
import com.shopmind.index.IndexKind;
import com.shopmind.index.IvfIndex;
import com.shopmind.index.Neighbor;
import com.shopmind.index.SearchParams;
import com.shopmind.index.VectorIndex;
import com.shopmind.service.embedding.EmbeddingService;
import com.shopmind.service.embedding.HashingTfIdfEmbedder;
import com.shopmind.service.model.ProductDoc;
import com.shopmind.service.store.KnowledgeBase;
import com.shopmind.service.store.WriteAheadLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 检索编排：统一管理三类向量索引与 BM25，向外提供"同一 query、可切换索引、可混合打分"的检索能力。
 *
 * <p>并发设计：索引对象本身用读写锁保护（检索走读锁、写入走写锁），
 * 因此"写入后立即可检索"与"检索期间不读到半成品"可以同时成立；
 * 全量重建用 synchronized 串行化，避免重建期间产生两套参数不同的索引并存。</p>
 *
 * <p>两种写入模式：{@link WriteMode#SYNC} 为写库 + 三索引同步落库的原始路径；
 * {@link WriteMode#DELTA} 为"WAL 顺序追加 + 内存 delta 可见 + 后台 applier 批量合并"，
 * 把 H2 刷盘与 HNSW 图插边移出热路径，单条入库耗时从毫秒级降到百微秒级。</p>
 */
public final class RetrievalService {

    /** 写入模式：SYNC 同步落库；DELTA WAL + 增量缓冲 + 后台合并。 */
    public enum WriteMode {
        SYNC, DELTA
    }

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    private final KnowledgeBase knowledgeBase;
    private final EmbeddingService embedder;
    private final Map<IndexKind, VectorIndex> indexes = new ConcurrentHashMap<>();
    private final Bm25Index bm25 = new Bm25Index();
    private final Object rebuildLock = new Object();
    /** 文档标题缓存：检索热路径上不做数据库查询，标题在这里一次性取好。 */
    private final Map<Long, String> titleCache = new ConcurrentHashMap<>();

    private final WriteMode writeMode;
    private WriteAheadLog wal;
    private DeltaBuffer delta;
    private Thread applier;
    private volatile boolean applierRunning;
    /** 诊断用：applier 暂停开关与等待对象。 */
    private final Object applierPauseLock = new Object();
    private volatile boolean applierPaused;

    public RetrievalService(KnowledgeBase knowledgeBase) {
        this(knowledgeBase, new HashingTfIdfEmbedder());
    }

    public RetrievalService(KnowledgeBase knowledgeBase, EmbeddingService embedder) {
        this(knowledgeBase, embedder, WriteMode.SYNC, null);
    }

    public RetrievalService(KnowledgeBase knowledgeBase, EmbeddingService embedder,
                            WriteMode writeMode, String walPath) {
        this.knowledgeBase = knowledgeBase;
        this.embedder = embedder;
        indexes.put(IndexKind.BRUTE_FORCE, new BruteForceIndex());
        indexes.put(IndexKind.IVF, new IvfIndex(256, 20261115L));
        indexes.put(IndexKind.HNSW, new HnswIndex(16, 200, 20261115L));
        this.writeMode = writeMode;
        if (writeMode == WriteMode.DELTA) {
            this.wal = WriteAheadLog.at(walPath);
            this.delta = new DeltaBuffer();
            startApplier();
        }
    }

    public EmbeddingService embedder() {
        return embedder;
    }

    public WriteMode writeMode() {
        return writeMode;
    }

    /**
     * 崩溃恢复：把 WAL 中未 checkpoint 的记录回放进 H2（MERGE 幂等），
     * 然后清空对应 WAL 段。必须在首次全量重建之前调用。
     */
    public void recover() {
        if (writeMode != WriteMode.DELTA) {
            return;
        }
        List<ProductDoc> pendingDocs = wal.recoverPending();
        if (pendingDocs.isEmpty()) {
            return;
        }
        log.info("WAL 恢复回放记录数={}", pendingDocs.size());
        knowledgeBase.saveAll(pendingDocs);
        wal.force();
        wal.checkpointAll();
    }

    /** 从数据库全量重建三套索引。重建期间对外可见的仍是旧索引，避免读到空索引。 */
    public void rebuildFromStore() {
        synchronized (rebuildLock) {
            List<ProductDoc> docs = knowledgeBase.findAll();
            log.info("开始重建索引，文档数={}", docs.size());

            // 第一遍先累计全局词频统计，第二遍再向量化，保证 idf 口径一致。
            if (embedder instanceof HashingTfIdfEmbedder hashing) {
                for (ProductDoc doc : docs) {
                    hashing.registerDocument(doc.searchableText());
                }
            }

            VectorIndex brute = new BruteForceIndex();
            VectorIndex ivf = new IvfIndex(256, 20261115L);
            VectorIndex hnsw = new HnswIndex(16, 200, 20261115L);

            for (ProductDoc doc : docs) {
                float[] vector = embedder.embed(doc.searchableText());
                brute.add(doc.id(), vector);
                ivf.add(doc.id(), vector);
                hnsw.add(doc.id(), vector);
                bm25.add(doc.id(), doc.searchableText());
            }
            if (ivf instanceof IvfIndex ivfIndex) {
                ivfIndex.build();
            }

            indexes.put(IndexKind.BRUTE_FORCE, brute);
            indexes.put(IndexKind.IVF, ivf);
            indexes.put(IndexKind.HNSW, hnsw);
            titleCache.clear();
            for (ProductDoc doc : docs) {
                titleCache.put(doc.id(), doc.title());
            }
            log.info("索引重建完成：brute={}, ivf={}, hnsw={}", brute.size(), ivf.size(), hnsw.size());
        }
    }

    /**
     * 新增文档。SYNC 模式：写库 → 向量化 → 同步写入三套索引与 BM25。
     * DELTA 模式：向量化 → WAL 顺序追加 → 入 delta 即可见，H2 与主索引由后台 applier 异步合并。
     * 返回端到端耗时（纳秒）。
     */
    public long addDocument(ProductDoc doc) {
        long start = System.nanoTime();
        String text = doc.searchableText();
        if (embedder instanceof HashingTfIdfEmbedder hashing) {
            hashing.registerDocument(text);
        }
        float[] vector = embedder.embed(text);

        if (writeMode == WriteMode.DELTA) {
            long walOffset = wal.append(doc);
            float[] normalized = Distance.normalizeInPlace(vector.clone());
            delta.submit(DeltaBuffer.pendingOf(doc, normalized, walOffset));
        } else {
            knowledgeBase.save(doc);
            for (VectorIndex index : indexes.values()) {
                index.add(doc.id(), vector);
            }
            bm25.add(doc.id(), text);
            titleCache.put(doc.id(), doc.title());
        }
        long elapsed = System.nanoTime() - start;
        log.debug("文档入库完成 id={} 模式={} 耗时={}us", doc.id(), writeMode, elapsed / 1000);
        return elapsed;
    }

    /**
     * 诊断用入库：分阶段耗时写入 phases（微秒），顺序为 register/embed/wal/deltaSubmit。
     */
    public long addDocumentProfiled(ProductDoc doc, long[] phases) {
        long t0 = System.nanoTime();
        String text = doc.searchableText();
        if (embedder instanceof HashingTfIdfEmbedder hashing) {
            hashing.registerDocument(text);
        }
        long t1 = System.nanoTime();
        float[] vector = embedder.embed(text);
        long t2 = System.nanoTime();
        long walOffset = wal.append(doc);
        long t3 = System.nanoTime();
        float[] normalized = Distance.normalizeInPlace(vector.clone());
        delta.submit(DeltaBuffer.pendingOf(doc, normalized, walOffset));
        long t4 = System.nanoTime();
        phases[0] = (t1 - t0) / 1000;
        phases[1] = (t2 - t1) / 1000;
        phases[2] = (t3 - t2) / 1000;
        phases[3] = (t4 - t3) / 1000;
        return t4 - t0;
    }

    public boolean deleteDocument(long id) {
        if (writeMode == WriteMode.DELTA) {
            delta.removeByDocId(id);
        }
        boolean removed = knowledgeBase.delete(id);
        for (VectorIndex index : indexes.values()) {
            index.remove(id);
        }
        bm25.remove(id);
        titleCache.remove(id);
        return removed;
    }

    /** 等待 delta 内全部记录被后台应用完毕，超时返回 false。 */
    public boolean awaitDrain(long timeoutMillis) throws InterruptedException {
        if (writeMode != WriteMode.DELTA) {
            return true;
        }
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (!delta.isEmpty()) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            Thread.sleep(1);
        }
        return true;
    }

    /** 命中项的标题（来自缓存，不在检索热路径上查库）。 */
    public String titleOf(long docId) {
        return titleCache.getOrDefault(docId, "");
    }

    /** 分页读取知识库文档，供管理页面直接渲染。 */
    public List<ProductDoc> listDocuments(int offset, int limit) {
        return knowledgeBase.list(offset, limit);
    }

    public long documentCount() {
        return knowledgeBase.count();
    }

    public java.util.Optional<ProductDoc> findDocument(long id) {
        return knowledgeBase.findById(id);
    }

    public SearchOutcome search(IndexKind kind, String query, SearchParams params, double alpha) {
        long start = System.nanoTime();
        VectorIndex index = indexes.get(kind);
        if (index == null) {
            throw new IllegalArgumentException("未知索引类型: " + kind);
        }

        float[] queryVector = embedder.embed(query);
        List<Neighbor> vectorHits = index.search(queryVector, params);

        List<Neighbor> hits;
        if (alpha >= 0.999) {
            hits = new ArrayList<>(vectorHits);
        } else {
            List<Neighbor> lexicalHits = bm25.search(query, Math.max(params.topK() * 3, 20));
            hits = HybridRanker.fuse(vectorHits, lexicalHits, alpha, params.topK());
        }

        if (writeMode == WriteMode.DELTA && !delta.isEmpty()) {
            float[] normalizedQuery = Distance.normalizeInPlace(queryVector.clone());
            List<Neighbor> deltaHits = delta.scan(normalizedQuery, params.topK());
            hits = mergeDelta(hits, deltaHits, params.topK());
        }
        return new SearchOutcome(kind, hits, System.nanoTime() - start);
    }

    /** 对同一 query 跑全部索引，供检索调试台做并排对照。 */
    public Map<IndexKind, SearchOutcome> searchAll(String query, SearchParams params, double alpha) {
        Map<IndexKind, SearchOutcome> outcomes = new LinkedHashMap<>();
        for (IndexKind kind : IndexKind.values()) {
            outcomes.put(kind, search(kind, query, params, alpha));
        }
        return outcomes;
    }

    /** 索引状态汇总，供监控看板与验证报告取数。 */
    public Map<String, Object> stats() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<IndexKind, VectorIndex> entry : indexes.entrySet()) {
            var stats = entry.getValue().stats();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("kind", stats.kind().label());
            item.put("size", stats.size());
            item.put("dimension", stats.dimension());
            item.put("maxLevel", stats.maxLevel());
            item.put("avgDegree", Math.round(stats.avgDegree() * 100) / 100.0);
            item.put("memoryBytes", stats.memoryBytes());
            result.put(entry.getKey().label(), item);
        }
        Map<String, Object> bm25Stats = new LinkedHashMap<>();
        bm25Stats.put("kind", "bm25");
        bm25Stats.put("size", bm25.size());
        bm25Stats.put("vocabulary", bm25.vocabularySize());
        result.put("bm25", bm25Stats);
        result.put("documents", knowledgeBase.count());
        result.put("writeMode", writeMode.name().toLowerCase());
        if (writeMode == WriteMode.DELTA) {
            result.put("deltaSize", delta.size());
            result.put("walPendingBytes", wal.pendingBytes());
        }
        return result;
    }

    /** 把一个查询向量与语料做精确暴力比对，供评测代码构造召回基准。 */
    public List<Neighbor> exactSearch(String query, int topK) {
        float[] q = embedder.embed(query);
        List<Neighbor> hits = indexes.get(IndexKind.BRUTE_FORCE).search(q, SearchParams.of(topK));
        List<Neighbor> copy = new ArrayList<>(hits.size());
        for (Neighbor n : hits) {
            copy.add(new Neighbor(n.docId(), n.score()));
        }
        return copy;
    }

    /** 计算归一化后的 L2 距离，供评测报告做分布分析。 */
    public double distance(String left, String right) {
        return Distance.squaredL2(embedder.embed(left), embedder.embed(right));
    }

    /**
     * 关闭：先等待后台队列排空（含 checkpoint），再停止 applier 并关闭 WAL。
     */
    public void close() {
        if (writeMode != WriteMode.DELTA) {
            return;
        }
        try {
            awaitDrain(30_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        applierRunning = false;
        if (applier != null) {
            applier.interrupt();
            try {
                applier.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        // 节流可能留下已应用但未 checkpoint 的数据，停机前补一次全量刷盘
        wal.force();
        wal.checkpointAll();
        wal.truncateApplied();
        wal.close();
    }

    /** 诊断：暂停 applier（当前条目处理完后在循环顶部挂起）。 */
    public void pauseApplier() {
        applierPaused = true;
    }

    /** 诊断：恢复 applier。 */
    public void resumeApplier() {
        applierPaused = false;
        synchronized (applierPauseLock) {
            applierPauseLock.notifyAll();
        }
    }

    private void startApplier() {
        applierRunning = true;
        applier = new Thread(this::applierLoop, "shopmind-applier");
        applier.setDaemon(true);
        // 后台合并线程优先级略低，保证前台写入与查询线程优先被调度
        applier.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
        applier.start();
    }

    /**
     * 后台合并循环：阻塞领取记录 → 落 H2 + 主索引 → drain 队列。
     * fsync + checkpoint 按 200 ms 节流，避免磁盘刷盘与热路径持续竞争；
     * 应用顺序与 WAL 顺序一致，保证偏移单调。
     */
    private void applierLoop() {
        long lastForceMillis = 0L;
        while (applierRunning || (delta != null && !delta.isEmpty())) {
            synchronized (applierPauseLock) {
                while (applierPaused) {
                    try {
                        applierPauseLock.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            DeltaBuffer.Pending first;
            try {
                first = delta.take();
            } catch (InterruptedException e) {
                if (!applierRunning && delta.isEmpty()) {
                    return;
                }
                Thread.interrupted();
                continue;
            }

            long maxOffset = applyPending(first);
            DeltaBuffer.Pending next;
            while ((next = delta.poll()) != null) {
                maxOffset = Math.max(maxOffset, applyPending(next));
            }
            long now = System.currentTimeMillis();
            if (now - lastForceMillis >= 200L) {
                wal.force();
                wal.checkpoint(maxOffset);
                lastForceMillis = now;
            }
        }
    }

    private long applyPending(DeltaBuffer.Pending entry) {
        ProductDoc doc = entry.doc();
        knowledgeBase.save(doc);
        for (VectorIndex index : indexes.values()) {
            index.add(doc.id(), entry.vector());
        }
        bm25.add(doc.id(), doc.searchableText());
        titleCache.put(doc.id(), doc.title());
        delta.markApplied(entry);
        return entry.walOffset();
    }

    /** 主索引结果与 delta 结果按 id 去重合并，按分数降序截断到 topK。 */
    private List<Neighbor> mergeDelta(List<Neighbor> mainHits, List<Neighbor> deltaHits, int topK) {
        Map<Long, Neighbor> merged = new LinkedHashMap<>();
        for (Neighbor hit : mainHits) {
            merged.put(hit.docId(), hit);
        }
        for (Neighbor hit : deltaHits) {
            merged.putIfAbsent(hit.docId(), hit);
        }
        List<Neighbor> all = new ArrayList<>(merged.values());
        all.sort((a, b) -> Float.compare(b.score(), a.score()));
        return all.size() > topK ? new ArrayList<>(all.subList(0, topK)) : all;
    }
}
