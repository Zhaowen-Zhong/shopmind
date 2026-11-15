package com.shopmind.index;

import java.util.List;

/**
 * 向量索引统一接口。
 *
 * <p>三类实现（暴力、IVF、HNSW）对外暴露完全一致的语义，评测代码才能"同一批向量、同一个评测集、
 * 只换实现"地做对照实验，这也是报告里三索引对照表的成立前提。</p>
 */
public interface VectorIndex {

    IndexKind kind();

    /** 向量维度；尚未写入任何向量时返回 -1。 */
    int dimension();

    /** 有效向量条数（不包含已删除）。 */
    int size();

    /**
     * 写入一条向量。实现内部负责归一化与维度校验。
     *
     * @throws IllegalArgumentException 维度与已写入向量不一致
     */
    void add(long docId, float[] vector);

    /** 按文档标识删除；返回是否存在且删除成功。 */
    boolean remove(long docId);

    /** 检索 Top-K，返回按相似度降序排列的结果。 */
    List<Neighbor> search(float[] query, SearchParams params);

    IndexStats stats();
}
