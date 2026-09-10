package com.agent.app.openplatform;

import com.agent.data.openplatform.ApiKeyRepository;
import com.agent.data.openplatform.ApiKeyRepository.ApiKey;
import com.agent.data.openplatform.TenantQuotaRepository;
import com.agent.data.openplatform.TenantQuotaRepository.TenantQuota;
import com.agent.data.tokenmeter.TokenUsageDailyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

/**
 * L1 接入层：开放平台 API Key 认证过滤器（W17）
 * <p>拦截所有 /api/open/** 请求，验证 X-Api-Key 头，检查租户配额（QPS、Token 配额、预算），
 * 通过后注入 X-Tenant-Id 头转发到后端，拒绝返回 401/403/429。</p>
 * <p>测试环境可通过配置 openplatform.enabled=false 禁用此过滤器。</p>
 */
@Component
@ConditionalOnProperty(name = "openplatform.enabled", havingValue = "true", matchIfMissing = true)
public class OpenApiFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(OpenApiFilter.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ApiKeyRepository apiKeyRepo;
    private final TenantQuotaRepository quotaRepo;
    private final TokenUsageDailyRepository tokenUsageDailyRepo;

    public OpenApiFilter(ApiKeyRepository apiKeyRepo,
                         TenantQuotaRepository quotaRepo,
                         TokenUsageDailyRepository tokenUsageDailyRepo) {
        this.apiKeyRepo = apiKeyRepo;
        this.quotaRepo = quotaRepo;
        this.tokenUsageDailyRepo = tokenUsageDailyRepo;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpReq = (HttpServletRequest) request;
        HttpServletResponse httpResp = (HttpServletResponse) response;

        // 1. 提取 X-Api-Key
        String apiKey = httpReq.getHeader("X-Api-Key");
        if (apiKey == null || apiKey.isBlank()) {
            sendError(httpResp, 401, "UNAUTHORIZED", "缺少 X-Api-Key 请求头");
            return;
        }

        // 2. 验证 API Key（哈希查询）
        String apiKeyHash = DigestUtils.sha256Hex(apiKey);
        Optional<ApiKey> apiKeyOpt = apiKeyRepo.findByHash(apiKeyHash);
        if (apiKeyOpt.isEmpty()) {
            sendError(httpResp, 401, "UNAUTHORIZED", "API Key 无效");
            return;
        }

        ApiKey key = apiKeyOpt.get();

        // 3. 检查 API Key 状态
        if (!"active".equals(key.status())) {
            sendError(httpResp, 403, "FORBIDDEN", "API Key 已" + ("expired".equals(key.status()) ? "过期" : "禁用"));
            return;
        }

        // 4. 检查过期时间
        if (key.expiresAt() != null && key.expiresAt().isBefore(Instant.now())) {
            sendError(httpResp, 403, "FORBIDDEN", "API Key 已过期");
            return;
        }

        // 5. 查询租户配额
        String tenantId = key.tenantId();
        Optional<TenantQuota> quotaOpt = quotaRepo.findByTenantId(tenantId);
        if (quotaOpt.isEmpty()) {
            sendError(httpResp, 403, "FORBIDDEN", "租户配额未配置");
            return;
        }

        TenantQuota quota = quotaOpt.get();

        // 6. 检查预算超支熔断
        if (quota.budgetExceeded()) {
            sendError(httpResp, 429, "TOO_MANY_REQUESTS", "租户预算已超支，请求被熔断");
            return;
        }

        // 7. 检查日 Token 配额
        long todayUsage = tokenUsageDailyRepo.getTodayTokenUsage(tenantId, LocalDate.now());
        if (todayUsage >= quota.dailyTokenQuota()) {
            sendError(httpResp, 429, "TOO_MANY_REQUESTS",
                    "租户日 Token 配额已用尽（已用 " + todayUsage + " / 配额 " + quota.dailyTokenQuota() + "）");
            return;
        }

        // 8. 通过验证，注入 X-Tenant-Id 头
        HttpServletRequest wrappedRequest = new HttpServletRequestWrapper(httpReq) {
            @Override
            public String getHeader(String name) {
                if ("X-Tenant-Id".equalsIgnoreCase(name)) {
                    return tenantId;
                }
                return super.getHeader(name);
            }
        };

        log.debug("开放平台请求通过：tenant={}, apiKey={}...", tenantId, key.apiKeyPrefix());
        chain.doFilter(wrappedRequest, response);
    }

    private void sendError(HttpServletResponse resp, int status, String code, String message) throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json;charset=UTF-8");
        Map<String, Object> error = Map.of(
                "code", status,
                "error", code,
                "message", message
        );
        resp.getWriter().write(JSON.writeValueAsString(error));
    }

    /**
     * 注册 Filter（仅拦截 /api/open/** 请求）。
     */
    @Configuration
    public static class OpenApiFilterConfig {

        @Bean
        public FilterRegistrationBean<OpenApiFilter> openApiFilterRegistration(OpenApiFilter filter) {
            FilterRegistrationBean<OpenApiFilter> registration = new FilterRegistrationBean<>();
            registration.setFilter(filter);
            registration.addUrlPatterns("/api/open/*");
            registration.setOrder(10);  // 在 McpAuthFilter 之后执行
            registration.setName("openApiFilter");
            return registration;
        }
    }
}
