package com.shopmind.gateway.dto;

/**
 * 检索请求载荷（承载在 SEARCH 帧的 body 中）。
 *
 * @param query    查询文本
 * @param topK     返回条数
 * @param efSearch HNSW 查询宽度，缺省用 SearchParams 的默认值
 * @param kind     索引类型：brute-force / ivf / hnsw
 * @param alpha    混合打分权重，1.0 表示纯向量检索
 */
public record SearchRequest(String query, int topK, int efSearch, String kind, double alpha) {

    public SearchRequest {
        if (topK <= 0) {
            topK = 10;
        }
        if (efSearch <= 0) {
            efSearch = 100;
        }
        if (alpha <= 0 || alpha > 1) {
            alpha = 0.8;
        }
    }
}
