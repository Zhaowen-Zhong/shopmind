package com.shopmind.gateway.dto;

/**
 * 握手请求载荷。
 *
 * @param token 访问令牌；网关按配置值校验，校验失败直接回 ERROR 帧并断开
 */
public record HandshakeRequest(String token) {
}
