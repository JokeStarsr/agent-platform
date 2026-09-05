package com.agent.tool.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * L5 MCP 网关：工具调用全量审计（docs/design/architecture/20260904-mcp-gateway.md §6）。
 * 结构化 JSON 写 logs/audit.log（AUDIT logger），经 MDC 自动带 trace_id。
 */
@Service
public class McpAuditService {

    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_LEN = 500;

    public void logAuthFail(String tenantId, String reason) {
        write("auth_fail", tenantId, null, null, null, 0, reason, 0);
    }

    public void logRateLimited(String tenantId, String method) {
        write("rate_limited", tenantId, method, null, null, 0, null, 0);
    }

    public void logToolsList(String tenantId, int toolCount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("phase", "tools_list");
        m.put("tenant_id", tenantId);
        m.put("tool_count", toolCount);
        writeRaw(m);
    }

    /** 工具调用结果审计（McpAuditAspect 或 AgentToolCallback 调用） */
    public void logToolsCall(String tenantId, String tool, boolean denied, int resultCode, long latencyMs) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("phase", "tools_call");
        m.put("tenant_id", tenantId);
        m.put("tool", tool);
        m.put("denied", denied);
        m.put("result_code", resultCode);
        m.put("latency_ms", latencyMs);
        writeRaw(m);
    }

    public void logUpDown(String phase, int toolCount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("phase", phase);
        m.put("tool_count", toolCount);
        writeRaw(m);
    }

    private void write(String phase, String tenantId, String method, String tool,
                       String detail, int code, String error, long latencyMs) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("phase", phase);
        m.put("tenant_id", tenantId);
        m.put("method", method);
        m.put("tool", tool);
        m.put("result_code", code);
        m.put("latency_ms", latencyMs);
        if (detail != null) {
            m.put("detail", truncate(detail));
        }
        if (error != null) {
            m.put("error", truncate(error));
        }
        writeRaw(m);
    }

    private void writeRaw(Map<String, Object> m) {
        try {
            AUDIT.info(JSON.writeValueAsString(m));
        } catch (Exception e) {
            AUDIT.warn("mcp audit serialize failed: {}", e.getMessage());
        }
    }

    private String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > MAX_LEN ? s.substring(0, MAX_LEN) : s;
    }
}
