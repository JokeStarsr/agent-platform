package com.agent.common;

import com.agent.capability.RagService.RagResult;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

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

    public AuditLogAspect(AuditService auditService) {
        this.auditService = auditService;
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

        if (result instanceof Result<?> r && r.getData() instanceof RagResult rag) {
            List<String> sourceChunks = rag.getSourceChunks();
            auditService.log(tenantId, query, topK,
                    sourceChunks == null ? 0 : sourceChunks.size(),
                    extractSources(rag.getCitations()), latencyMs, 0,
                    rag.isNeedsHandoff(), rag.getHandoffReason(), rag.getConfidenceScore(),
                    rag.getAnswer() == null ? 0 : rag.getAnswer().length());
        } else {
            auditService.logRequest(tenantId, query, topK);
        }
        return result;
    }

    @Around("execution(* com.agent.capability.RagService.streamSearch(..))")
    public Object auditStreamRequest(ProceedingJoinPoint pjp) throws Throwable {
        Object[] args = pjp.getArgs();
        String query = (String) args[0];
        int topK = args[1] == null ? 5 : (Integer) args[1];
        String tenantId = (String) args[2];
        auditService.logRequest(tenantId, query, topK);
        return pjp.proceed();
    }

    /** 引用列表【1】: 文件名 → 提取文件名 */
    private List<String> extractSources(List<String> citations) {
        if (citations == null) return List.of();
        return citations.stream()
                .map(c -> c.contains(":") ? c.substring(c.indexOf(":") + 1).trim() : c)
                .toList();
    }
}
