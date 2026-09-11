package com.agent.access;

import com.agent.capability.security.PromptInjectionDetector;
import com.agent.capability.security.PromptInjectionDetector.DetectionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * L1 接入层：注入检测守卫过滤器（冲刺 1，安全红线接入管道）
 * <p>对 POST /api/** 请求体做 Prompt 注入检测，命中即 403 拦截（宁可误拦不可漏判）。
 * 与 AccessGateFilter 同层，Order 略低（trace/tenant 已注入 MDC 后可打日志）。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@ConditionalOnProperty(name = "app.security.injection-filter", havingValue = "true", matchIfMissing = true)
public class InjectionGuardFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(InjectionGuardFilter.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final PromptInjectionDetector detector;

    public InjectionGuardFilter(PromptInjectionDetector detector) {
        this.detector = detector;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // 仅扫描 POST + /api/ 路径 + JSON 内容
        if (!"POST".equalsIgnoreCase(request.getMethod())
                || !request.getRequestURI().startsWith("/api/")) {
            chain.doFilter(request, response);
            return;
        }
        String ctype = request.getContentType();
        if (ctype == null || !ctype.contains("application/json")) {
            chain.doFilter(request, response);
            return;
        }

        // 包一层可重复读 body 的 request
        CachedBodyHttpServletRequest cached = new CachedBodyHttpServletRequest(request);
        String body = cached.getBody();

        if (body == null || body.isBlank()) {
            chain.doFilter(cached, response);
            return;
        }

        DetectionResult result = detector.detect(body, request.getRequestURI());
        if (result.blocked()) {
            log.warn("注入守卫拦截: uri={}, category={}, message={}",
                    request.getRequestURI(), result.category(), result.message());
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(JSON.writeValueAsString(Map.of(
                    "code", 403,
                    "error", "INJECTION_BLOCKED",
                    "message", result.message()
            )));
            return;
        }

        chain.doFilter(cached, response);
    }
}