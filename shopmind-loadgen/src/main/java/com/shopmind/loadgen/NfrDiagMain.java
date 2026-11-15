package com.shopmind.loadgen;

import com.shopmind.service.embedding.HashingTfIdfEmbedder;
import com.shopmind.service.model.ProductDoc;
import com.shopmind.service.retrieval.RetrievalService;
import com.shopmind.service.store.KnowledgeBase;

import java.util.ArrayList;
import java.util.List;

/**
 * NFR-06 诊断：同一进程内 A/B 对照热路径分阶段耗时。
 * A 段 applier 活跃；B 段 applier 暂停（delta 已排空）。
 * 用于定位并发 applier 下热路径被放大的具体阶段。
 */
public final class NfrDiagMain {

    private static final int WARMUP = 10;
    private static final int SAMPLES = 40;

    private NfrDiagMain() {
    }

    public static void main(String[] args) throws InterruptedException {
        String path = args.length > 0 ? args[0] : "./data/shopmind";
        KnowledgeBase knowledgeBase = KnowledgeBase.file(path);
        RetrievalService retrieval = new RetrievalService(knowledgeBase,
                new HashingTfIdfEmbedder(), RetrievalService.WriteMode.DELTA, path);
        retrieval.recover();

        long rebuildStart = System.currentTimeMillis();
        retrieval.rebuildFromStore();
        System.out.printf("索引重建完成，耗时 %d ms%n",
                System.currentTimeMillis() - rebuildStart);

        // 预热（不计入）
        for (int i = 0; i < WARMUP; i++) {
            retrieval.addDocument(doc(930_000_000L + i, i));
        }

        // A 段：applier 活跃
        List<long[]> blockA = new ArrayList<>();
        for (int i = 0; i < SAMPLES; i++) {
            long[] phases = new long[4];
            retrieval.addDocumentProfiled(doc(940_000_000L + i, i), phases);
            blockA.add(phases);
            Thread.sleep(20);
        }

        // 暂停 applier 并排空
        retrieval.pauseApplier();
        long waitDeadline = System.currentTimeMillis() + 30_000;
        while (retrieval.stats().get("deltaSize") == null
                || ((Number) retrieval.stats().get("deltaSize")).intValue() > 0) {
            if (System.currentTimeMillis() > waitDeadline) {
                throw new IllegalStateException("等待 applier 排空超时");
            }
            Thread.sleep(2);
        }
        Thread.sleep(500);

        // B 段：applier 暂停
        List<long[]> blockB = new ArrayList<>();
        for (int i = 0; i < SAMPLES; i++) {
            long[] phases = new long[4];
            retrieval.addDocumentProfiled(doc(950_000_000L + i, i), phases);
            blockB.add(phases);
            Thread.sleep(20);
        }

        printBlock("A applier 活跃", blockA);
        printBlock("B applier 暂停", blockB);

        retrieval.resumeApplier();
        retrieval.close();
        knowledgeBase.close();
    }

    private static ProductDoc doc(long id, int i) {
        String unique = "诊断专用词" + id;
        return new ProductDoc(id, unique + " 智能商品",
                "这是一条 NFR 诊断文档，编号 " + i, "诊断");
    }

    private static void printBlock(String title, List<long[]> block) {
        String[] names = {"register", "embed", "walAppend", "deltaSubmit"};
        System.out.println("==== " + title + " ====");
        for (int phase = 0; phase < 4; phase++) {
            List<Long> values = new ArrayList<>();
            for (long[] row : block) {
                values.add(row[phase]);
            }
            values.sort(Long::compare);
            long avg = Math.round(values.stream().mapToLong(Long::longValue).average().orElse(0));
            System.out.printf("%-12s avg=%4d p50=%4d p99=%4d max=%4d%n",
                    names[phase], avg, values.get(values.size() / 2),
                    values.get((int) Math.ceil(values.size() * 0.99) - 1),
                    values.get(values.size() - 1));
        }
        List<Long> totals = new ArrayList<>();
        for (long[] row : block) {
            long sum = 0;
            for (long v : row) {
                sum += v;
            }
            totals.add(sum);
        }
        totals.sort(Long::compare);
        long avgTotal = Math.round(totals.stream().mapToLong(Long::longValue).average().orElse(0));
        System.out.printf("%-12s avg=%4d p50=%4d p99=%4d max=%4d%n",
                "TOTAL", avgTotal, totals.get(totals.size() / 2),
                totals.get((int) Math.ceil(totals.size() * 0.99) - 1),
                totals.get(totals.size() - 1));
    }
}
