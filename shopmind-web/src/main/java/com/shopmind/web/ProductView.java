package com.shopmind.web;

/**
 * 商品在前端的视图对象：在 {@link com.shopmind.service.model.ProductDoc} 基础上
 * 补充展示用的参考价、类目序号（决定缩略图配色）与可选的检索相关度。
 *
 * @param score 检索相关度；浏览模式下为 null
 */
public record ProductView(long id, String title, String description, String category,
                          Double score, String price, int categoryIndex) {
}
