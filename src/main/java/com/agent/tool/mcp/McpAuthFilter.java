package com.agent.tool.mcp;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * L5 MCP 网关：统一鉴权中间件（docs/design/architecture/20260904-mcp-gateway.md §3.3）。
 * <p>仅作用于 MCP 协议端点（/mcp 前缀，SSE + message）。管理 API（/api/mcp）不经过本过滤器。
 * 凭证：X-Tenant-Id（必填，不再默认 default）+ X-Api-Key / Authorization Bearer（与租户绑定）。
 * 校验通过 → 写 MDC tenant_id + McpTenantContext；失败 → 401/403 并落审计。</p>
 * <p>通过 {@code McpWebConfig.FilterRegistrationBean} 注册（URL 限定 /mcp/*，order 由注册Bean指定），
 * 而非 {@code @Component}：裸注解会在 {@code @WebMvcTest} 切片被提前实例化、要求 {@code McpProperties}
 * bean 而起不来上下文（设计 §3.3/§9.1）。MDC key 用字面量 "tenant_id"（与 AccessGateFilter 一致），
 * 避免 Tool→Access 跨层依赖（ArchitectureTest 拒绝 Access 被任何层依赖）。</p>
 */
public class McpAuthFilter extends OncePerRequestFilter {

    public static final String TENANT_HEADER = "X-Tenant-Id";
    public static final String API_KEY_HEADER = "X-Api-Key";

    private final McpProperties properties;
    private final McpAuditService audit;
    private final McpRateLimiter rateLimiter;

    public McpAuthFilter(McpProperties properties, McpAuditService audit, McpRateLimiter rateLimiter) {
        this.properties = properties;
        this.audit = audit;
        this.rateLimiter = rateLimiter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(McpConstants.ENDPOINT_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String tenantId = request.getHeader(TENANT_HEADER);
        String apiKey = request.getHeader(API_KEY_HEADER);
        if (apiKey == null || apiKey.isBlank()) {
            String auth = request.getHeader("Authorization");
            if (auth != null && auth.startsWith("Bearer ")) {
                apiKey = auth.substring("Bearer ".length());
            }
        }

        if (tenantId == null || tenantId.isBlank()) {
            audit.logAuthFail(null, "缺少 X-Tenant-Id");
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.getWriter().write("missing tenant");
            return;
        }
        String expected = properties.getApiKeys().get(tenantId);
        if (expected == null || !expected.equals(apiKey)) {
            audit.logAuthFail(tenantId, "API Key 校验失败或该租户未配置");
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.getWriter().write("invalid api key");
            return;
        }

        // 租户级全局限流（超限 → 429 + 审计）
        if (!rateLimiter.tryAcquire(tenantId)) {
            audit.logRateLimited(tenantId, request.getMethod());
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.getWriter().write("rate limited");
            return;
        }

        MDC.put("tenant_id", tenantId);
        McpTenantContext.set(tenantId);
        try {
            chain.doFilter(request, response);
        } finally {
            McpTenantContext.clear();
            MDC.remove("tenant_id");
        }
    }
}
