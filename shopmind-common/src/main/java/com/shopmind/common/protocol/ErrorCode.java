package com.shopmind.common.protocol;

/**
 * 错误码。
 *
 * <p>每个错误码都对应一个稳定的数值，客户端可据此做分支处理；
 * 协议层错误（1xx）直接断开连接，业务层错误（2xx）保留连接并返回 ERROR 帧。</p>
 */
public enum ErrorCode {

    INVALID_MAGIC(101, "帧魔数校验失败"),
    UNSUPPORTED_VERSION(102, "协议版本不支持"),
    BODY_TOO_LARGE(103, "帧体超出长度上限"),
    UNKNOWN_COMMAND(104, "未知命令码"),
    MALFORMED_PAYLOAD(105, "载荷解析失败"),
    UNAUTHORIZED(201, "未通过鉴权"),
    RATE_LIMITED(202, "触发限流"),
    INDEX_UNAVAILABLE(203, "索引未就绪"),
    INTERNAL_ERROR(500, "服务内部错误");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int code() {
        return code;
    }

    public String message() {
        return message;
    }
}
