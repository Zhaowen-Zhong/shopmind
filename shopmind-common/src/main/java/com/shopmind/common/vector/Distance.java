package com.shopmind.common.vector;

/**
 * 向量相似度工具。
 *
 * <p>统一约定：入库与查询向量都先做 L2 归一化，则余弦相似度退化为点积，
 * 检索内层循环只剩一次乘法累加，这是本项目在 Java 侧达到目标 QPS 的前提。
 * 因此 HNSW 内部使用的"距离"定义为 1 - similarity，越小越近。</p>
 */
public final class Distance {

    private Distance() {
    }

    /** 原地 L2 归一化；零向量保持原样（后续由调用方按业务规则拒绝写入）。 */
    public static float[] normalizeInPlace(float[] vector) {
        double sum = 0.0;
        for (float v : vector) {
            sum += (double) v * v;
        }
        double norm = Math.sqrt(sum);
        if (norm == 0.0) {
            return vector;
        }
        float inv = (float) (1.0 / norm);
        for (int i = 0; i < vector.length; i++) {
            vector[i] *= inv;
        }
        return vector;
    }

    /** 点积，要求两向量同维且已归一化。 */
    public static float dot(float[] a, float[] b) {
        int dim = a.length;
        float sum = 0f;
        for (int i = 0; i < dim; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }

    /** 余弦相似度（不要求已归一化，内部自行算范数）。 */
    public static float cosine(float[] a, float[] b) {
        double dot = 0.0;
        double na = 0.0;
        double nb = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na == 0.0 || nb == 0.0) {
            return 0f;
        }
        return (float) (dot / Math.sqrt(na * nb));
    }

    /** 欧氏距离平方，避免开方开销。 */
    public static float squaredL2(float[] a, float[] b) {
        float sum = 0f;
        for (int i = 0; i < a.length; i++) {
            float d = a[i] - b[i];
            sum += d * d;
        }
        return sum;
    }

    /** 归一化后向量之间的"距离"，等价于 1 - cosine。 */
    public static float cosineDistance(float[] a, float[] b) {
        return 1f - dot(a, b);
    }
}
