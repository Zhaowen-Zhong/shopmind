package com.shopmind.gateway.dto;

import java.util.List;

/**
 * 检索应答载荷。
 *
 * @param index         实际使用的索引
 * @param hits          命中列表
 * @param latencyMicros 服务端处理耗时（微秒）
 * @param seq           对应请求的序列号，便于客户端做流水线匹配
 */
public record SearchResponse(String index, List<HitDto> hits, double latencyMicros, int seq) {
}
