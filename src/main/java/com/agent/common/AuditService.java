package com.agent.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 审计日志服务（P1 收口 #4，docs/design/api/20260830-audit-chatui.md §2.1）
 * <p>
 * 记一条结构化 JSON 日志（logger="AUDIT" → logs/audit.log），经 MDC 自动带 trace_id。
 * 字段：谁(tenant_id)/何时(ts 由日志自带)/问了什么(query)/检索命中(chunkCount,sources)/
 * 延迟(latencyMs,firstTokenMs)/转人工判定(needsHandoff,handoffReason,confidenceScore)。
 * 真实 token 计数属 L6 计量拦截器（CLAUDE.md 铁律），P2 接入；P1 用 answerLen 作代理。
 */
@Service
public class AuditService {

    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int QUERY_MAX = 500;

    public void log(String tenantId, String query, int topK, int chunkCount, List<String> sources,
                    long latencyMs, long firstTokenMs, boolean needsHandoff, String handoffReason,
                    double confidenceScore, int answerLen) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tenant_id", tenantId);
        m.put("query", truncate(query));
        m.put("topK", topK);
        m.put("chunkCount", chunkCount);
        m.put("sources", sources == null ? List.of() : sources.stream().limit(5).toList());
        m.put("latencyMs", latencyMs);
        m.put("firstTokenMs", firstTokenMs);
        m.put("needsHandoff", needsHandoff);
        m.put("handoffReason", handoffReason);
        m.put("confidenceScore", Math.round(confidenceScore * 1000) / 1000.0);
        m.put("answerLen", answerLen);
        write(m);
    }

    /** 请求入口审计（流式端点：完整结果在 done 事件，由 RagService 补记完成审计） */
    public void logRequest(String tenantId, String query, int topK) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tenant_id", tenantId);
        m.put("query", truncate(query));
        m.put("topK", topK);
        m.put("phase", "request");
        write(m);
    }

    private String truncate(String s) {
        if (s == null) return "";
        return s.length() > QUERY_MAX ? s.substring(0, QUERY_MAX) : s;
    }

    private void write(Map<String, Object> m) {
        try {
            AUDIT.info(JSON.writeValueAsString(m));
        } catch (Exception e) {
            AUDIT.warn("audit serialize failed: {}", e.getMessage());
        }
    }
}
