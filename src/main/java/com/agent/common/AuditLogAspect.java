package com.agent.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 审计日志切面（P1 收口 #4，docs/design/api/20260830-audit-chatui.md §2.1）
 * <p>
 * - 同步 search：结果完整，环绕记全量审计（检索命中/延迟/转人工/置信度）
 * - 流式 streamSearch：请求入口审计；完整结果在 Flux 的 done 事件里，由 RagService 补记完成审计
 */
@Aspect
@Component
@ConditionalOnProperty(name = "app.audit.enabled", havingValue = "true")
public class AuditLogAspect {

    private final AuditService auditService;
    // 反射式读 Result.data 审计字段：common 是跨层基础设施，不 import 具体业务类型（七层依赖只允许向下）
    private final ObjectMapper dataMapper = new ObjectMapper();
    // PII 脱敏端口（capability.security.PiiMasker 实现），按需注入实现日志自动掩码
    private final PiiMaskPort piiMaskPort;

    public AuditLogAspect(AuditService auditService, org.springframework.beans.factory.ObjectProvider<PiiMaskPort> piiMaskPortProvider) {
        this.auditService = auditService;
        this.piiMaskPort = piiMaskPortProvider.getIfAvailable();
    }

    @Around("execution(* com.agent.capability.RagService.search(..))")
    public Object auditSearch(ProceedingJoinPoint pjp) throws Throwable {
        long startNs = System.nanoTime();
        Object result = pjp.proceed();
        long latencyMs = (System.nanoTime() - startNs) / 1_000_000;
        Object[] args = pjp.getArgs();
        String query = (String) args[0];
        int topK = args[1] == null ? 5 : (Integer) args[1];
        String tenantId = (String) args[2];
        String safeQuery = maskPii(query);

        if (result instanceof Result<?> r && r.getData() != null) {
            Map<String, Object> data = dataMapper.convertValue(r.getData(), Map.class);
            List<?> sourceChunks = asList(data.get("sourceChunks"));
            List<?> citations = asList(data.get("citations"));
            Object answer = data.get("answer");
            auditService.log(tenantId, safeQuery, topK,
                    sourceChunks == null ? 0 : sourceChunks.size(),
                    extractSources(citations), latencyMs, 0,
                    Boolean.TRUE.equals(data.get("needsHandoff")),
                    String.valueOf(data.getOrDefault("handoffReason", "NONE")),
                    data.get("confidenceScore") instanceof Number n ? n.doubleValue() : 0.0,
                    answer == null ? 0 : String.valueOf(answer).length());
        } else {
            auditService.logRequest(tenantId, safeQuery, topK);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<?> asList(Object v) {
        return v instanceof List<?> l ? l : null;
    }

    @Around("execution(* com.agent.capability.RagService.streamSearch(..))")
    public Object auditStreamRequest(ProceedingJoinPoint pjp) throws Throwable {
        Object[] args = pjp.getArgs();
        String query = (String) args[0];
        int topK = args[1] == null ? 5 : (Integer) args[1];
        String tenantId = (String) args[2];
        auditService.logRequest(tenantId, maskPii(query), topK);
        return pjp.proceed();
    }

    /** PII 脱敏（无实现时原样返回） */
    private String maskPii(String text) {
        if (piiMaskPort != null) {
            try {
                return piiMaskPort.maskSensitive(text);
            } catch (Exception e) {
                // 脱敏失败不阻断主流程
            }
        }
        return text;
    }

    /** 引用列表【1】: 文件名 → 提取文件名 */
    private List<String> extractSources(List<?> citations) {
        if (citations == null) return List.of();
        return citations.stream()
                .map(String::valueOf)
                .map(c -> c.contains(":") ? c.substring(c.indexOf(":") + 1).trim() : c)
                .toList();
    }
}
