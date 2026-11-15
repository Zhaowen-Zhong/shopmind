package com.shopmind.service.retrieval;

import com.shopmind.index.IndexKind;
import com.shopmind.index.Neighbor;

import java.util.List;

/**
 * 一次检索的完整结果：命中列表 + 端到端耗时。
 *
 * <p>把耗时一起返回，是为了让"检索调试台"和压测客户端都能拿到同一口径的延迟数字，
 * 避免报告里出现两套互相矛盾的数据。</p>
 *
 * @param kind         使用的索引类型
 * @param hits         命中列表
 * @param latencyNanos 端到端耗时（纳秒）
 */
public record SearchOutcome(IndexKind kind, List<Neighbor> hits, long latencyNanos) {

    public double latencyMicros() {
        return latencyNanos / 1000.0;
    }
}
