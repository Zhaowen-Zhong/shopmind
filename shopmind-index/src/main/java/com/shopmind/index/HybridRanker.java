package com.shopmind.index;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 混合打分器：把向量召回（语义相近）与 BM25 召回（字面命中）融合成一个排序。
 *
 * <p>两路分值量纲完全不同（余弦在 [-1,1]，BM25 无上界），直接相加会被某一方压制，
 * 因此先各自做 min-max 归一化，再按 alpha 加权。alpha=1 退化为纯向量检索，
 * alpha=0 退化为纯关键词检索——这个参数正好可以在评测集上做敏感性实验。</p>
 */
public final class HybridRanker {

    private HybridRanker() {
    }

    public static List<Neighbor> fuse(List<Neighbor> vectorHits,
                                      List<Neighbor> lexicalHits,
                                      double alpha,
                                      int topK) {
        if (alpha < 0 || alpha > 1) {
            throw new IllegalArgumentException("alpha 必须位于 [0,1]: " + alpha);
        }

        Map<Long, Double> fused = new HashMap<>();
        Map<Long, Double> normalizedVector = normalize(vectorHits);
        Map<Long, Double> normalizedLexical = normalize(lexicalHits);

        for (Map.Entry<Long, Double> entry : normalizedVector.entrySet()) {
            fused.merge(entry.getKey(), alpha * entry.getValue(), Double::sum);
        }
        for (Map.Entry<Long, Double> entry : normalizedLexical.entrySet()) {
            fused.merge(entry.getKey(), (1 - alpha) * entry.getValue(), Double::sum);
        }

        List<Neighbor> result = new ArrayList<>(fused.size());
        for (Map.Entry<Long, Double> entry : fused.entrySet()) {
            result.add(new Neighbor(entry.getKey(), entry.getValue().floatValue()));
        }
        result.sort((a, b) -> Float.compare(b.score(), a.score()));

        // 去重后截断：两路结果可能命中同一文档，fused 已是按 docId 合并后的唯一集合。
        Set<Long> seen = new LinkedHashSet<>();
        List<Neighbor> truncated = new ArrayList<>(Math.min(topK, result.size()));
        for (Neighbor n : result) {
            if (seen.add(n.docId())) {
                truncated.add(n);
                if (truncated.size() >= topK) {
                    break;
                }
            }
        }
        return truncated;
    }

    /** min-max 归一化；当所有分值相同（例如只有一条命中）时统一给 1.0。 */
    private static Map<Long, Double> normalize(List<Neighbor> hits) {
        Map<Long, Double> map = new HashMap<>(hits.size() * 2);
        if (hits.isEmpty()) {
            return map;
        }
        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        for (Neighbor n : hits) {
            min = Math.min(min, n.score());
            max = Math.max(max, n.score());
        }
        double range = max - min;
        for (Neighbor n : hits) {
            double value = range == 0.0 ? 1.0 : (n.score() - min) / range;
            map.merge(n.docId(), value, Math::max);
        }
        return map;
    }
}
