package com.agent.access;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * L1接入层过滤器（链路入口，合并原 TraceIdFilter 职责）
 * 核心职责：
 * 1. trace_id 自动注入/透传（MDC，全链路关联）
 * 2. tenant_id 解析与校验（基础框架，后续接入 SSO/OIDC）
 * 3. 基础流量治理占位（后续接入 Redis QPS 熔断器）
 * 4. 审计日志入口标记（具体写入由网关/应用层负责）
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AccessGateFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String TRACE_ID_MDC_KEY = "trace_id";
    public static final String TENANT_ID_HEADER = "X-Tenant-Id";
    public static final String TENANT_ID_MDC_KEY = "tenant_id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        // 1. trace_id：优先透传外部值，否则生成新值
        String traceId = request.getHeader(TRACE_ID_HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        MDC.put(TRACE_ID_MDC_KEY, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);

        // 2. tenant_id：默认 "default"，后续由 SSO/OIDC 注入真实租户
        String tenantId = request.getHeader(TENANT_ID_HEADER);
        if (tenantId == null || tenantId.isBlank()) {
            tenantId = "default";
        }
        MDC.put(TENANT_ID_MDC_KEY, tenantId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(TRACE_ID_MDC_KEY);
            MDC.remove(TENANT_ID_MDC_KEY);
        }
    }
}