package com.shopmind.gateway.net;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 网关运行指标。
 *
 * <p>这些计数器是"长连接规模"与"限流生效"两项验收指标的唯一数据来源，
 * 监控看板与压测报告都从这里取数，避免出现两套口径。</p>
 */
public final class GatewayMetrics {

    private final AtomicInteger activeConnections = new AtomicInteger();
    private final AtomicLong totalConnections = new AtomicLong();
    private final AtomicLong totalSearches = new AtomicLong();
    private final AtomicLong rejectedByLimit = new AtomicLong();
    private final AtomicLong unauthorized = new AtomicLong();

    public void connectionOpened() {
        activeConnections.incrementAndGet();
        totalConnections.incrementAndGet();
    }

    public void connectionClosed() {
        activeConnections.decrementAndGet();
    }

    public void searchHandled() {
        totalSearches.incrementAndGet();
    }

    public void rejected() {
        rejectedByLimit.incrementAndGet();
    }

    public void unauthorized() {
        unauthorized.incrementAndGet();
    }

    public int activeConnections() {
        return activeConnections.get();
    }

    public long totalConnections() {
        return totalConnections.get();
    }

    public long totalSearches() {
        return totalSearches.get();
    }

    public long rejectedByLimit() {
        return rejectedByLimit.get();
    }

    public long unauthorizedCount() {
        return unauthorized.get();
    }
}
