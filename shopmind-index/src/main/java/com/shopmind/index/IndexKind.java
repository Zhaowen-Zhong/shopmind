package com.shopmind.index;

/** 索引类型。 */
public enum IndexKind {

    /** 暴力检索，作为召回质量的精确基准。 */
    BRUTE_FORCE("brute-force"),
    /** 倒排聚类索引。 */
    IVF("ivf"),
    /** 分层可导航小世界图索引。 */
    HNSW("hnsw");

    private final String label;

    IndexKind(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
