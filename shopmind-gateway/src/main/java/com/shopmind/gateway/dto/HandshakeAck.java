package com.shopmind.gateway.dto;

/**
 * 握手应答载荷。
 *
 * @param sessionId        会话标识，用于日志追踪
 * @param heartbeatSeconds 建议客户端心跳间隔（秒）
 * @param serverTime       服务端时间戳（毫秒），供客户端做时钟偏差校正
 */
public record HandshakeAck(String sessionId, int heartbeatSeconds, long serverTime) {
}
