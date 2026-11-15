package com.shopmind.common.protocol;

/**
 * 协议异常。
 *
 * <p>区分两类处理：协议层错误（魔数、版本、长度越界）在解码阶段抛出，由网关关闭连接；
 * 业务层错误承载具体错误码，通过 ERROR 帧回传，连接继续可用。</p>
 */
public class ProtocolException extends RuntimeException {

    private final ErrorCode errorCode;

    public ProtocolException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ProtocolException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
