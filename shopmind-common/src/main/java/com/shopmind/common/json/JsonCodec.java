package com.shopmind.common.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JSON 编解码。
 *
 * <p>单例复用 ObjectMapper（其内部有序列化器缓存，重复创建会显著拖慢短请求）；
 * 关闭"未知字段报错"，让协议后续扩字段时对旧客户端保持兼容。</p>
 */
public final class JsonCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private JsonCodec() {
    }

    public static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("序列化失败: " + value.getClass().getName(), e);
        }
    }

    public static <T> T fromJson(byte[] payload, Class<T> type) {
        try {
            return MAPPER.readValue(payload, type);
        } catch (Exception e) {
            throw new IllegalArgumentException("反序列化失败: " + type.getName(), e);
        }
    }

    public static <T> T fromJson(String payload, Class<T> type) {
        try {
            return MAPPER.readValue(payload, type);
        } catch (Exception e) {
            throw new IllegalArgumentException("反序列化失败: " + type.getName(), e);
        }
    }
}
