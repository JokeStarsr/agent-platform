package com.agent.tool.mcp;

import com.agent.tool.ToolRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * L5 MCP 网关：Server 注册中心 / 健康检查 / 上下线事件（docs/design/architecture/20260904-mcp-gateway.md §3.6）。
 * 单服务部署，进程内轻量实现：登记元数据 + 健康检查端点 + up/down 审计事件，W11 南向接入时再升级。
 */
@Component
public class McpServerRegistry {

    private final ToolRegistry toolRegistry;
    private final McpAuditService audit;
    private final long startedAt = System.currentTimeMillis();

    public McpServerRegistry(ToolRegistry toolRegistry, McpAuditService audit) {
        this.toolRegistry = toolRegistry;
        this.audit = audit;
    }

    @PostConstruct
    void onUp() {
        audit.logUpDown("up", toolCount());
    }

    @PreDestroy
    void onDown() {
        audit.logUpDown("down", toolCount());
    }

    private int toolCount() {
        return toolRegistry.listTools().size();
    }

    /** 健康检查：工具数为 0 视为 DEGRADED（未装配工具） */
    public Map<String, Object> health() {
        Map<String, Object> m = new LinkedHashMap<>();
        int n = toolCount();
        m.put("status", n == 0 ? "DEGRADED" : "UP");
        m.put("server", "agent-platform");
        m.put("endpoint", McpConstants.ENDPOINT_PREFIX);
        m.put("toolCount", n);
        m.put("uptimeMs", System.currentTimeMillis() - startedAt);
        return m;
    }
}
