package com.agent.tool.mcp;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * L5 MCP 网关：租户级全局限流（内存令牌桶，v1 单实例够用；分布式限流留 P3 与 AccessGateFilter 合并演进）。
 */
@Component
public class McpRateLimiter {

    private final McpProperties properties;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public McpRateLimiter(McpProperties properties) {
        this.properties = properties;
    }

    /** 尝试消费一个令牌；返回 false 表示超限 */
    public boolean tryAcquire(String tenantId) {
        int qps = Math.max(1, properties.getRateLimit().getGlobalQps());
        long now = System.currentTimeMillis();
        Bucket b = buckets.computeIfAbsent(tenantId, k -> new Bucket(qps));
        synchronized (b) {
            long window = now / 1000;
            if (b.window != window) {
                b.window = window;
                b.count.set(0);
            }
            if (b.count.incrementAndGet() > qps) {
                return false;
            }
            return true;
        }
    }

    private static final class Bucket {
        final int limit;
        long window = -1;
        final AtomicLong count = new AtomicLong();

        Bucket(int limit) {
            this.limit = limit;
        }
    }
}
