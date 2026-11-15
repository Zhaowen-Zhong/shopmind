package com.shopmind.service.embedding;

import com.shopmind.common.vector.Distance;
import com.shopmind.index.TextTokenizer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于哈希投影的 TF-IDF 向量化器（纯 Java，零模型依赖）。
 *
 * <p>流程：分词 → 统计词频 → 计算 tf-idf 权重 → 用 token 的哈希值把权重散列到固定维度的桶里（带符号），
 * 最后做 L2 归一化。哈希投影会带来少量冲突，但换来的是"无需词表、维度固定、可在线增量"，
 * 在商品标题这类短文本上足够用；它同时是 DJL 语义向量接入失败时的保底链路。</p>
 */
public final class HashingTfIdfEmbedder implements EmbeddingService {

    private final int dimension;
    private final Map<String, Integer> documentFrequency = new HashMap<>();
    private long documentCount;

    public HashingTfIdfEmbedder() {
        this(128);
    }

    public HashingTfIdfEmbedder(int dimension) {
        if (dimension <= 0) {
            throw new IllegalArgumentException("维度必须为正数: " + dimension);
        }
        this.dimension = dimension;
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public float[] embed(String text) {
        List<String> tokens = TextTokenizer.tokenize(text);
        if (tokens.isEmpty()) {
            return new float[dimension];
        }

        Map<String, Integer> termFrequency = new HashMap<>();
        for (String token : tokens) {
            termFrequency.merge(token, 1, Integer::sum);
        }

        float[] vector = new float[dimension];
        for (Map.Entry<String, Integer> entry : termFrequency.entrySet()) {
            String token = entry.getKey();
            double tf = 1.0 + Math.log(entry.getValue());
            double idf = inverseDocumentFrequency(token);
            double weight = tf * idf;

            int bucket = Math.floorMod(hash(token), dimension);
            int sign = (hash(token) & 0x80000000) == 0 ? 1 : -1;
            vector[bucket] += (float) (sign * weight);
        }
        return Distance.normalizeInPlace(vector);
    }

    /** 把本文档的词频统计并入全局 df，供后续向量计算使用。 */
    public void registerDocument(String text) {
        List<String> tokens = TextTokenizer.tokenize(text);
        for (String token : tokens) {
            documentFrequency.merge(token, 1, Integer::sum);
        }
        if (!tokens.isEmpty()) {
            documentCount++;
        }
    }

    public long documentCount() {
        return documentCount;
    }

    public int vocabularySize() {
        return documentFrequency.size();
    }

    private double inverseDocumentFrequency(String token) {
        int df = documentFrequency.getOrDefault(token, 0);
        return Math.log(1.0 + (documentCount + 1.0) / (df + 1.0)) + 1.0;
    }

    /** 稳定的字符串哈希，保证同一 token 在任何 JVM 上都落到同一个桶。 */
    private static int hash(String token) {
        int h = 0;
        for (int i = 0; i < token.length(); i++) {
            h = 31 * h + token.charAt(i);
        }
        // 扰动，避免短词的哈希低位规律性过强导致桶分布不均
        h ^= (h >>> 16);
        h *= 0x85EBCA6B;
        h ^= (h >>> 13);
        return h;
    }
}
