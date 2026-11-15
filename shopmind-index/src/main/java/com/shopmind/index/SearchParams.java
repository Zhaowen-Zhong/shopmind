package com.shopmind.index;

/**
 * 检索参数。
 *
 * <p>把不同索引的调节参数放进同一个对象，方便评测时对同一 query 用不同参数重复调用，
 * 也便于把参数原样写进实验记录（报告里的参数-召回-延迟曲线需要这些值）。</p>
 *
 * @param topK    返回条数
 * @param efSearch HNSW 查询期搜索宽度，越大越精确越慢
 * @param nprobe  IVF 查询时扫描的聚类簇数
 */
public record SearchParams(int topK, int efSearch, int nprobe) {

    /**
     * 默认查询期搜索宽度。由 efSearch 扫描实验确定：50 时 Recall@10 约 0.86，
     * 100 时约 0.96，延迟增加约 40%，因此取 100 作为"召回达标记、延迟可控"的折中点。
     */
    public static final int DEFAULT_EF_SEARCH = 100;
    public static final int DEFAULT_NPROBE = 8;

    public SearchParams {
        if (topK <= 0) {
            throw new IllegalArgumentException("topK 必须为正数: " + topK);
        }
        if (efSearch < topK) {
            throw new IllegalArgumentException("efSearch 不能小于 topK: " + efSearch + " < " + topK);
        }
        if (nprobe <= 0) {
            throw new IllegalArgumentException("nprobe 必须为正数: " + nprobe);
        }
    }

    public static SearchParams of(int topK) {
        return new SearchParams(topK, Math.max(DEFAULT_EF_SEARCH, topK), DEFAULT_NPROBE);
    }

    public SearchParams withEfSearch(int ef) {
        return new SearchParams(topK, ef, nprobe);
    }

    public SearchParams withNprobe(int probe) {
        return new SearchParams(topK, efSearch, probe);
    }
}
