package com.shopmind.index;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HNSW 召回率测试：以暴力检索为精确基准，验证近似索引的召回质量。
 *
 * <p>这个测试同时是报告里"召回质量"指标的最小可复现实验——同样的种子、同样的参数，
 * 任何人 clone 仓库后跑 mvn test 都能得到一致结论。</p>
 */
class HnswRecallTest {

    private static final int N = 3000;
    private static final int DIM = 64;
    private static final int QUERIES = 100;
    private static final int K = 10;

    @Test
    void hnswRecallShouldBeatThreshold() {
        Random random = new Random(42L);
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

        assertEquals(N, hnsw.size(), "写入条数应与语料一致");
        assertEquals(DIM, hnsw.dimension());

        double recallSum = 0.0;
        SearchParams params = new SearchParams(K, SearchParams.DEFAULT_EF_SEARCH, 8);
        for (int q = 0; q < QUERIES; q++) {
            float[] query = randomVector(random, DIM);
            List<Neighbor> exact = brute.search(query, params);
            List<Neighbor> approx = hnsw.search(query, params);

            Set<Long> exactIds = new HashSet<>();
            for (Neighbor n : exact) {
                exactIds.add(n.docId());
            }
            int hit = 0;
            for (Neighbor n : approx) {
                if (exactIds.contains(n.docId())) {
                    hit++;
                }
            }
            recallSum += (double) hit / K;
        }

        double recall = recallSum / QUERIES;
        System.out.printf("HNSW Recall@%d = %.4f (N=%d, dim=%d, M=%d, efConstruction=%d, efSearch=%d)%n",
                K, recall, N, DIM, 16, 200, params.efSearch());

        assertTrue(recall >= 0.90, "HNSW 召回率应达到 0.90 以上，实际为 " + recall);
    }

    @Test
    void searchOnEmptyIndexShouldReturnEmptyList() {
        HnswIndex hnsw = new HnswIndex();
        List<Neighbor> result = hnsw.search(randomVector(new Random(7L), DIM), SearchParams.of(K));
        assertTrue(result.isEmpty(), "空索引检索应返回空集合而不是抛异常");
    }

    @Test
    void dimensionMismatchShouldBeRejected() {
        HnswIndex hnsw = new HnswIndex();
        hnsw.add(1L, new float[]{1f, 0f, 0f});
        IllegalArgumentException ex = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> hnsw.add(2L, new float[]{1f, 0f}));
        assertEquals("向量维度不一致: 期望 3，实际 2", ex.getMessage());
    }

    @Test
    void deletedDocumentShouldNotBeReturned() {
        HnswIndex hnsw = new HnswIndex();
        float[] v = new float[DIM];
        v[0] = 1f;
        hnsw.add(1L, v);
        hnsw.add(2L, randomVector(new Random(3L), DIM));

        assertTrue(hnsw.remove(1L), "删除应成功");
        List<Neighbor> result = hnsw.search(v, SearchParams.of(K));
        List<Long> ids = new ArrayList<>();
        for (Neighbor n : result) {
            ids.add(n.docId());
        }
        assertTrue(!ids.contains(1L), "已删除文档不应出现在检索结果中");
    }

    private static float[] randomVector(Random random, int dim) {
        float[] v = new float[dim];
        for (int i = 0; i < dim; i++) {
            v[i] = (float) (random.nextGaussian());
        }
        return v;
    }
}
