package com.agent.tool.mcp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

class McpAuthFilterTest {

    private McpProperties props;
    private McpAuditService audit;
    private McpRateLimiter rateLimiter;
    private McpAuthFilter filter;

    @BeforeEach
    void setUp() {
        props = new McpProperties();
        props.setApiKeys(Map.of("t1", "key-t1", "t2", "key-t2"));
        audit = mock(McpAuditService.class);
        rateLimiter = new McpRateLimiter(props);
        filter = new McpAuthFilter(props, audit, rateLimiter);
    }

    @Test
    void missingTenant_returns401() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/mcp/sse");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(req, resp, chain);

        assertEquals(401, resp.getStatus());
    }

    @Test
    void invalidApiKey_returns403() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/mcp/message");
        req.addHeader("X-Tenant-Id", "t1");
        req.addHeader("X-Api-Key", "wrong-key");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(req, resp, chain);

        assertEquals(403, resp.getStatus());
    }

    @Test
    void validCredentials_passesThrough() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/mcp/message");
        req.addHeader("X-Tenant-Id", "t1");
        req.addHeader("X-Api-Key", "key-t1");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(req, resp, chain);

        assertEquals(200, resp.getStatus());
        // MDC 应该在 filter 退出后被清理
        assertNull(McpTenantContext.get());
    }

    @Test
    void bearerAuth_passesThrough() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/mcp/message");
        req.addHeader("X-Tenant-Id", "t2");
        req.addHeader("Authorization", "Bearer key-t2");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(req, resp, chain);

        assertEquals(200, resp.getStatus());
    }

    @Test
    void rateLimited_returns429() throws Exception {
        props.getRateLimit().setGlobalQps(1);
        McpRateLimiter strictLimiter = new McpRateLimiter(props);
        McpAuthFilter strictFilter = new McpAuthFilter(props, audit, strictLimiter);

        // 第一次通过
        MockHttpServletRequest req1 = new MockHttpServletRequest("POST", "/mcp/message");
        req1.addHeader("X-Tenant-Id", "t1");
        req1.addHeader("X-Api-Key", "key-t1");
        strictFilter.doFilterInternal(req1, new MockHttpServletResponse(), new MockFilterChain());

        // 第二次超限
        MockHttpServletRequest req2 = new MockHttpServletRequest("POST", "/mcp/message");
        req2.addHeader("X-Tenant-Id", "t1");
        req2.addHeader("X-Api-Key", "key-t1");
        MockHttpServletResponse resp2 = new MockHttpServletResponse();
        strictFilter.doFilterInternal(req2, resp2, new MockFilterChain());

        assertEquals(429, resp2.getStatus());
    }

    @Test
    void shouldNotFilter_returnsTrueForNonMcpPaths() {
        // shouldNotFilter 由 OncePerRequestFilter 框架调用，决定是否跳过本过滤器
        assertEquals(false, filter.shouldNotFilter(new MockHttpServletRequest("POST", "/mcp/message")));
        assertEquals(false, filter.shouldNotFilter(new MockHttpServletRequest("GET", "/mcp/sse")));
        assertEquals(true, filter.shouldNotFilter(new MockHttpServletRequest("GET", "/api/mcp/health")));
        assertEquals(true, filter.shouldNotFilter(new MockHttpServletRequest("GET", "/api/rag/search")));
    }
}
