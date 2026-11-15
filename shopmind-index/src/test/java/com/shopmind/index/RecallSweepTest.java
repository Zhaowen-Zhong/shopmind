package com.shopmind.index;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * efSearch 参数扫描实验：同一语料、同一查询集，只改 efSearch，观察召回率与单次检索耗时。
 *
 * <p>这组数据直接对应报告里的"参数-召回-延迟曲线"，也是确定默认 efSearch 的依据。</p>
 */
class RecallSweepTest {

    private static final int N = 5000;
    private static final int DIM = 64;
    private static final int QUERIES = 200;
    private static final int K = 10;
    private static final int[] EF_VALUES = {20, 30, 50, 80, 120, 200};

    @Test
    void sweepEfSearch() {
        Random random = new Random(2026L);
        float[][] corpus = new float[N][];
        for (int i = 0; i < N; i++) {
            corpus[i] = randomVector(random, DIM);
        }

        BruteForceIndex brute = new BruteForceIndex();
        HnswIndex hnsw = new HnswIndex(16, 200, 20261115L);
        for (int i = 0; i < N; i++) {
            brute.add(i, corpus[i]);
            hnsw.add(i, corpus[i]);
        }

        float[][] queries = new float[QUERIES][];
        for (int q = 0; q < QUERIES; q++) {
            queries[q] = randomVector(random, DIM);
        }

        SearchParams exactParams = new SearchParams(K, 128, 8);
        List<Set<Long>> exactSets = new ArrayList<>(QUERIES);
        for (int q = 0; q < QUERIES; q++) {
            Set<Long> ids = new HashSet<>();
            for (Neighbor n : brute.search(queries[q], exactParams)) {
                ids.add(n.docId());
            }
            exactSets.add(ids);
        }

        System.out.println("efSearch,recall@" + K + ",avgLatencyMicros");
        for (int ef : EF_VALUES) {
            SearchParams params = new SearchParams(K, ef, 8);
            // 预热，避免 JIT 冷启动污染第一批样本
            for (int q = 0; q < QUERIES; q++) {
                hnsw.search(queries[q], params);
            }

            double recallSum = 0.0;
            long totalNanos = 0L;
            for (int q = 0; q < QUERIES; q++) {
                long start = System.nanoTime();
                List<Neighbor> approx = hnsw.search(queries[q], params);
                totalNanos += System.nanoTime() - start;

                int hit = 0;
                for (Neighbor n : approx) {
                    if (exactSets.get(q).contains(n.docId())) {
                        hit++;
                    }
                }
                recallSum += (double) hit / K;
            }
            double recall = recallSum / QUERIES;
            double avgMicros = (double) totalNanos / QUERIES / 1000.0;
            System.out.printf("%d,%.4f,%.1f%n", ef, recall, avgMicros);
        }
    }

    private static float[] randomVector(Random random, int dim) {
        float[] v = new float[dim];
        for (int i = 0; i < dim; i++) {
            v[i] = (float) random.nextGaussian();
        }
        return v;
    }
}
