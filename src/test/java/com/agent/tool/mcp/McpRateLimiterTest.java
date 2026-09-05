package com.agent.tool.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpRateLimiterTest {

    @Test
    void allowsUpToQpsThenRejects() {
        McpProperties props = new McpProperties();
        props.getRateLimit().setGlobalQps(3);
        McpRateLimiter limiter = new McpRateLimiter(props);

        assertTrue(limiter.tryAcquire("t1"));
        assertTrue(limiter.tryAcquire("t1"));
        assertTrue(limiter.tryAcquire("t1"));
        assertFalse(limiter.tryAcquire("t1")); // 超限
    }

    @Test
    void tenantsAreIndependent() {
        McpProperties props = new McpProperties();
        props.getRateLimit().setGlobalQps(1);
        McpRateLimiter limiter = new McpRateLimiter(props);

        assertTrue(limiter.tryAcquire("t1"));
        assertFalse(limiter.tryAcquire("t1"));
        assertTrue(limiter.tryAcquire("t2")); // 不同租户互不影响
    }
}
