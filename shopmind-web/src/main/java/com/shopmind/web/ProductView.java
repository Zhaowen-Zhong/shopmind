package com.shopmind.web;

import java.util.List;

/**
 * 商品在前端的视图对象：在 {@link com.shopmind.service.model.ProductDoc} 基础上
 * 去除内部型号、提取差异化卖点，并补充参考价、类目序号（决定配色与图标）与可选相关度。
 *
 * @param displayTitle 去掉内部型号后的营销标题
 * @param sellingPoints 从描述中提取的差异化卖点（属性 / 场景）
 * @param score 检索相关度；浏览模式下为 null
 */
public record ProductView(long id, String displayTitle, String description, String category,
                          List<String> sellingPoints, Double score, String price,
                          int categoryIndex) {
}
