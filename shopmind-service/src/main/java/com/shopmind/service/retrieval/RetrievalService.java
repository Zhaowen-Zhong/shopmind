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
 */
public final class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    private final KnowledgeBase knowledgeBase;
    private final EmbeddingService embedder;
    private final Map<IndexKind, VectorIndex> indexes = new ConcurrentHashMap<>();
    private final Bm25Index bm25 = new Bm25Index();
    private final Object rebuildLock = new Object();
    /** 文档标题缓存：检索热路径上不做数据库查询，标题在这里一次性取好。 */
    private final Map<Long, String> titleCache = new ConcurrentHashMap<>();

    public RetrievalService(KnowledgeBase knowledgeBase) {
        this(knowledgeBase, new HashingTfIdfEmbedder());
    }

    public RetrievalService(KnowledgeBase knowledgeBase, EmbeddingService embedder) {
        this.knowledgeBase = knowledgeBase;
        this.embedder = embedder;
        indexes.put(IndexKind.BRUTE_FORCE, new BruteForceIndex());
        indexes.put(IndexKind.IVF, new IvfIndex(256, 20261115L));
        indexes.put(IndexKind.HNSW, new HnswIndex(16, 200, 20261115L));
    }

    public EmbeddingService embedder() {
        return embedder;
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

    /** 新增一条文档：写库 → 向量化 → 同步写入三套索引与 BM25。返回端到端耗时。 */
    public long addDocument(ProductDoc doc) {
        long start = System.nanoTime();
        knowledgeBase.save(doc);
        String text = doc.searchableText();
        if (embedder instanceof HashingTfIdfEmbedder hashing) {
            hashing.registerDocument(text);
        }
        float[] vector = embedder.embed(text);
        for (VectorIndex index : indexes.values()) {
            index.add(doc.id(), vector);
        }
        bm25.add(doc.id(), text);
        titleCache.put(doc.id(), doc.title());
        long elapsed = System.nanoTime() - start;
        log.debug("文档入库完成 id={} 耗时={}us", doc.id(), elapsed / 1000);
        return elapsed;
    }

    public boolean deleteDocument(long id) {
        boolean removed = knowledgeBase.delete(id);
        if (removed) {
            for (VectorIndex index : indexes.values()) {
                index.remove(id);
            }
            bm25.remove(id);
            titleCache.remove(id);
        }
        return removed;
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
            hits = vectorHits;
        } else {
            List<Neighbor> lexicalHits = bm25.search(query, Math.max(params.topK() * 3, 20));
            hits = HybridRanker.fuse(vectorHits, lexicalHits, alpha, params.topK());
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
}
