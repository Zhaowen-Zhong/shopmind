package com.shopmind.index;

/**
 * 检索命中的一条结果。
 *
 * @param docId 文档标识
 * @param score 余弦相似度，取值 [-1, 1]，越大越相近
 */
public record Neighbor(long docId, float score) {
}
