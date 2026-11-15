package com.shopmind.index;

/**
 * 索引运行状态快照，供监控看板与《系统验证报告》直接取数。
 *
 * @param kind        索引类型
 * @param size        有效向量条数
 * @param dimension   向量维度
 * @param maxLevel    HNSW 最高层级，其他索引为 0
 * @param avgDegree   平均邻居数，衡量图稠密程度
 * @param memoryBytes 向量与邻接表占用的估算字节数
 */
public record IndexStats(IndexKind kind, int size, int dimension, int maxLevel,
                         double avgDegree, long memoryBytes) {
}
