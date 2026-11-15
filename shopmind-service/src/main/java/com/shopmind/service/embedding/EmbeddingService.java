package com.shopmind.service.embedding;

/**
 * 文本向量化接口。
 *
 * <p>把它抽成接口是为了保留两种可切换的实现：默认的 {@link HashingTfIdfEmbedder} 不依赖任何模型文件，
 * 保证老师在离线环境 clone 下来即可跑通；DJL/ONNX 的语义向量实现作为可选增强，
 * 两者产出的向量维度约定一致，切换后索引与评测代码无需改动。</p>
 */
public interface EmbeddingService {

    int dimension();

    /** 把文本编码为定长向量，内部完成归一化。 */
    float[] embed(String text);
}
