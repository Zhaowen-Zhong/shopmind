package com.shopmind.loadgen;

import com.shopmind.index.IndexKind;
import com.shopmind.index.Neighbor;
import com.shopmind.index.SearchParams;
import com.shopmind.service.model.ProductDoc;
import com.shopmind.service.retrieval.RetrievalService;
import com.shopmind.service.store.KnowledgeBase;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * NFR-06 增量写入验收：测量"单文档入库到可检索"的端到端耗时。
 *
 * <p>计时口径覆盖完整链路：H2 写入 → 向量化 → 三套向量索引同步写入 → BM25 写入；
 * 每次写入后立即用暴力索引检索该文档的唯一短语，确认"可检索"而不是只写未生效。
 * 预热阶段额外拆分各阶段耗时，用于报告中的归因分析。
 * 注意：运行前应用必须停止，否则 H2 文件锁冲突。</p>
 */
public final class IncrementalMain {

    private static final int WARMUP = 5;
    private static final int ITERATIONS = 30;

    private IncrementalMain() {
    }

    public static void main(String[] args) {
        String path = args.length > 0 ? args[0] : "./data/shopmind";

        KnowledgeBase knowledgeBase = KnowledgeBase.file(path);
        RetrievalService retrieval = new RetrievalService(knowledgeBase);
        long rebuildStart = System.currentTimeMillis();
        retrieval.rebuildFromStore();
        System.out.printf("基线索引重建完成：%d 条，耗时 %d ms%n",
                retrieval.documentCount(), System.currentTimeMillis() - rebuildStart);

        // 阶段耗时探针（仅预热期），单位微秒
        long saveSum = 0;
        long embedSum = 0;
        long totalSum = 0;

        List<Long> samples = new ArrayList<>();
        for (int i = 0; i < WARMUP + ITERATIONS; i++) {
            long id = 900_000_000L + i;
            String unique = "增量验收专用词" + id;
            ProductDoc doc = new ProductDoc(
                    id,
                    unique + " 智能商品",
                    "这是一条用于 NFR-06 增量写入耗时测量的文档，编号 " + i,
                    "增量验收");

            long elapsedMicros;
            if (i < WARMUP) {
                long t0 = System.nanoTime();
                knowledgeBase.save(doc);
                long t1 = System.nanoTime();
                retrieval.embedder().embed(doc.searchableText());
                long t2 = System.nanoTime();

                // addDocument 会重新写库与向量化，这里取它的完整口径做总耗时
                long totalNanos = retrieval.addDocument(doc);
                long t3 = System.nanoTime();

                saveSum += (t1 - t0) / 1000;
                embedSum += (t2 - t1) / 1000;
                totalSum += (t3 - t0) / 1000;
                elapsedMicros = totalNanos / 1000;
            } else {
                elapsedMicros = retrieval.addDocument(doc) / 1000;
            }

            // 立刻检索，确认写入对查询可见
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

        System.out.printf("阶段探针（预热均值，微秒）：H2写入=%d，向量化=%d，全链路（含重复写库）=%d%n",
                saveSum / WARMUP, embedSum / WARMUP, totalSum / WARMUP);

        samples.sort(Comparator.naturalOrder());
        long avg = Math.round(samples.stream().mapToLong(Long::longValue).average().orElse(0));
        long p50 = percentile(samples, 50);
        long p95 = percentile(samples, 95);
        long p99 = percentile(samples, 99);
        long max = samples.get(samples.size() - 1);

        System.out.println("iterations,avg_us,p50_us,p95_us,p99_us,max_us,target_us,verdict");
        System.out.printf("%d,%d,%d,%d,%d,%d,500,%s%n",
                ITERATIONS, avg, p50, p95, p99, max, p99 <= 500 ? "PASS" : "FAIL");

        knowledgeBase.close();
    }

    private static long percentile(List<Long> sorted, int pct) {
        int index = (int) Math.ceil(pct / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, index));
    }
}
