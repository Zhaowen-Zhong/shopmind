package com.shopmind.gateway.net;

/**
 * 令牌桶限流器（每连接一个实例）。
 *
 * <p>用"容量 + 按时间补充"的经典桶算法：突发流量可以吃掉桶内存量，但长期平均速率被限制在 refillPerSecond，
 * 超限时返回 RATE_LIMITED 而不是静默丢弃——压测时需要看到明确的拒绝计数，而不是说不清的"丢包"。</p>
 */
public final class TokenBucket {

    private final double capacity;
    private final double refillPerSecond;
    private double tokens;
    private long lastRefillNanos;

    public TokenBucket(double capacity, double refillPerSecond) {
        if (capacity <= 0 || refillPerSecond <= 0) {
            throw new IllegalArgumentException("容量与补充速率必须为正数");
        }
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
        this.tokens = capacity;
        this.lastRefillNanos = System.nanoTime();
    }

    public synchronized boolean tryAcquire() {
        refill();
        if (tokens >= 1.0) {
            tokens -= 1.0;
            return true;
        }
        return false;
    }

    private void refill() {
        long now = System.nanoTime();
        double elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0;
        if (elapsedSeconds <= 0) {
            return;
        }
        tokens = Math.min(capacity, tokens + elapsedSeconds * refillPerSecond);
        lastRefillNanos = now;
    }
}
