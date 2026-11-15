package com.shopmind.gateway.dto;

/**
 * 单个命中项，直接用于页面渲染与压测结果落盘。
 *
 * @param id    文档标识
 * @param title 商品标题（便于压测时人工抽查结果是否合理）
 * @param score 相关度分数
 */
public record HitDto(long id, String title, float score) {
}
