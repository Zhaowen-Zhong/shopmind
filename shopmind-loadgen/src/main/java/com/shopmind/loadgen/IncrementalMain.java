package com.shopmind.loadgen;

import com.shopmind.index.IndexKind;
import com.shopmind.index.Neighbor;
import com.shopmind.index.SearchParams;
import com.shopmind.service.embedding.HashingTfIdfEmbedder;
import com.shopmind.service.model.ProductDoc;
import com.shopmind.service.retrieval.RetrievalService;
import com.shopmind.service.store.KnowledgeBase;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * NFR-06 增量写入验收：测量"单文档入库到可检索"的端到端耗时。
 *
 * <p>DELTA 口径（WAL + 内存 delta + 后台 applier）：计时覆盖热路径完整动作——
 * 向量化 → WAL 顺序追加 → 入 delta 缓冲；每次写入后立即检索文档唯一短语，
 * 确认"可检索"而不是只写未生效（此时由 delta 层命中）。
 * 结束后等待后台 applier 把全部记录落入 H2 与三套主索引并完成 WAL checkpoint，
 * 再做二次核验，确认最终一致、无丢失。</p>
 */
public final class IncrementalMain {

    private static final int WARMUP = 5;
    private static final int ITERATIONS = 30;

    private IncrementalMain() {
    }

    public static void main(String[] args) throws InterruptedException {
        String path = args.length > 0 ? args[0] : "./data/shopmind";

        KnowledgeBase knowledgeBase = KnowledgeBase.file(path);
        RetrievalService retrieval = new RetrievalService(knowledgeBase,
                new HashingTfIdfEmbedder(), RetrievalService.WriteMode.DELTA, path);
        retrieval.recover();
        long baseCount = knowledgeBase.count();

        long rebuildStart = System.currentTimeMillis();
        retrieval.rebuildFromStore();
        System.out.printf("基线索引重建完成：%d 条，耗时 %d ms%n",
                retrieval.documentCount(), System.currentTimeMillis() - rebuildStart);

        // 阶段耗时探针（仅预热期），单位微秒
        long embedSum = 0;
        long totalSum = 0;

        List<Long> samples = new ArrayList<>();
        List<long[]> phaseRows = new ArrayList<>();
        for (int i = 0; i < WARMUP + ITERATIONS; i++) {
            long id = 900_000_000L + i;
            String unique = "增量验收专用词" + id;
            ProductDoc doc = new ProductDoc(
                    id,
                    unique + " 智能商品",
                    "这是一条用于 NFR-06 增量写入耗时测量的文档，编号 " + i,
                    "增量验收");

            long elapsedMicros;
            long[] phases = new long[4];
            if (i < WARMUP) {
                long t0 = System.nanoTime();
                retrieval.embedder().embed(doc.searchableText());
                long t1 = System.nanoTime();

                long totalNanos = retrieval.addDocument(doc);
                long t2 = System.nanoTime();

                embedSum += (t1 - t0) / 1000;
                totalSum += (t2 - t0) / 1000;
                elapsedMicros = totalNanos / 1000;
            } else {
                elapsedMicros = retrieval.addDocumentProfiled(doc, phases) / 1000;
                phaseRows.add(phases);
            }

            // 立刻检索，确认写入对查询可见（delta 层命中）
            List<Neighbor> hits = retrieval.search(
                    IndexKind.BRUTE_FORCE, unique, SearchParams.of(10), 1.0).hits();
            boolean visible = hits.stream().anyMatch(h -> h.docId() == id);
            if (!visible) {
                throw new IllegalStateException("文档 " + id + " 写入后未被检索到，验收失败");
            }

            if (i >= WARMUP) {
                samples.add(elapsedMicros);
            }
        }

        System.out.printf("阶段探针（预热均值，微秒）：向量化=%d，delta 全链路=%d%n",
                embedSum / WARMUP, totalSum / WARMUP);

        samples.sort(Comparator.naturalOrder());
        long avg = Math.round(samples.stream().mapToLong(Long::longValue).average().orElse(0));
        long p50 = percentile(samples, 50);
        long p95 = percentile(samples, 95);
        long p99 = percentile(samples, 99);
        long max = samples.get(samples.size() - 1);

        System.out.println("iterations,avg_us,p50_us,p95_us,p99_us,max_us,target_us,verdict");
        System.out.printf("%d,%d,%d,%d,%d,%d,500,%s%n",
                ITERATIONS, avg, p50, p95, p99, max, p99 <= 500 ? "PASS" : "FAIL");

        // 慢样本分阶段拆解（register/embed/wal/delta，微秒）
        System.out.println("慢样本（总耗时>450μs）分阶段拆解：");
        for (int row = 0; row < phaseRows.size(); row++) {
            long[] ph = phaseRows.get(row);
            long total = ph[0] + ph[1] + ph[2] + ph[3];
            if (total > 450) {
                System.out.printf("sample#%d total=%d register=%d embed=%d wal=%d delta=%d%n",
                        row, total, ph[0], ph[1], ph[2], ph[3]);
            }
        }

        // 等待后台 applier 全部落库并 checkpoint
        boolean drained = retrieval.awaitDrain(30_000);
        long expectedCount = baseCount + WARMUP + ITERATIONS;
        long finalCount = knowledgeBase.count();
        System.out.printf("后台落库：drained=%s，H2 最终条数=%d，期望=%d%n",
                drained, finalCount, expectedCount);

        if (!drained || finalCount != expectedCount) {
            throw new IllegalStateException("后台落库不一致，数据丢失或超时");
        }

        // 二次核验：全部新文档在主索引（非 delta）中可检索
        for (int i = 0; i < WARMUP + ITERATIONS; i++) {
            long id = 900_000_000L + i;
            String unique = "增量验收专用词" + id;
            List<Neighbor> hits = retrieval.search(
                    IndexKind.BRUTE_FORCE, unique, SearchParams.of(10), 1.0).hits();
            if (hits.stream().noneMatch(h -> h.docId() == id)) {
                throw new IllegalStateException("文档 " + id + " 主索引核验失败");
            }
        }
        System.out.println("一致性核验通过：全部新文档已落入 H2 与主索引，WAL 已 checkpoint");

        retrieval.close();
        knowledgeBase.close();
    }

    private static long percentile(List<Long> sorted, int pct) {
        int index = (int) Math.ceil(pct / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, index));
    }
}
