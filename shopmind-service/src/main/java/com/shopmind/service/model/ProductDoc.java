package com.shopmind.service.model;

/**
 * 商品知识库文档。
 *
 * @param id          文档标识（与索引中的 docId 一致）
 * @param title       商品标题
 * @param description 商品描述，参与向量化与关键词检索
 * @param category    类目，用于看板分组统计
 */
public record ProductDoc(long id, String title, String description, String category) {

    /** 用于向量化与 BM25 的完整文本：标题权重更高，因此重复一次。 */
    public String searchableText() {
        return title + " " + title + " " + description;
    }
}
