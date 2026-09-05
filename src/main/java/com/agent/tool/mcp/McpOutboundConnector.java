package com.agent.tool.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * L5 MCP 网关：出站 MCP Client 连接器（docs/design/architecture/20260904-mcp-gateway.md §4）。
 * <p>管理对外部 MCP Server 的连接生命周期。每个 enabled 的 OutboundTarget 在启动时建立 SSE 连接，
 * 远端工具包装为 ToolCallback 供平台内 Agent（AgentRuntime / Workflow）消费。</p>
 * <p>W10 骨架：连接器结构 + 状态管理 + 管理 API。真实外部 MCP Server 接入留 W11 工具市场。</p>
 * <p>审计：出站调用同样记录 tool/target/latency/resultCode（共用 McpAuditService）。</p>
 */
@Component
public class McpOutboundConnector {

    private static final Logger log = LoggerFactory.getLogger(McpOutboundConnector.class);

    private final McpOutboundProperties properties;
    private final McpAuditService audit;

    /** targetName → 连接状态 */
    private final Map<String, ConnectionState> connections = new ConcurrentHashMap<>();

    /** targetName → 远端工具回调列表 */
    private final Map<String, List<ToolCallback>> remoteTools = new ConcurrentHashMap<>();

    public McpOutboundConnector(McpOutboundProperties properties, McpAuditService audit) {
        this.properties = properties;
        this.audit = audit;
    }

    @PostConstruct
    void init() {
        for (McpOutboundProperties.OutboundTarget target : properties.getTargets()) {
            if (!target.isEnabled()) {
                connections.put(target.getName(), new ConnectionState(target, "DISABLED", 0));
                log.info("MCP 出站目标 [{}] 已禁用，跳过连接", target.getName());
                continue;
            }
            connectTarget(target);
        }
    }

    /**
     * 连接一个外部 MCP Server（W10 骨架）。
     * <p>实际实现需要 spring-ai-mcp-client 的 HttpClientSseClientTransport + SyncMcpClient，
     * W11 工具市场阶段接入真实外部 Server。当前仅记录连接状态。</p>
     */
    private void connectTarget(McpOutboundProperties.OutboundTarget target) {
        try {
            // W10 骨架：记录连接意图，实际 SSE 连接留 W11
            // 真实实现代码（W11）：
            //   HttpClientSseClientTransport transport = new HttpClientSseClientTransport(target.getUrl());
            //   McpClient client = McpClient.sync(transport)
            //       .requestTimeout(Duration.ofMillis(target.getRequestTimeoutMs()))
            //       .build();
            //   client.initialize();
            //   List<Tool> tools = client.listTools();
            //   ToolCallbackProvider provider = new SyncMcpToolCallbackProvider(client);
            //   remoteTools.put(target.getName(), Arrays.asList(provider.getToolCallbacks()));
            connections.put(target.getName(), new ConnectionState(target, "SKELETON", 0));
            log.info("MCP 出站目标 [{}] 骨架已注册（url={}），真实连接留 W11", target.getName(), target.getUrl());
        } catch (Exception e) {
            connections.put(target.getName(), new ConnectionState(target, "ERROR", 0));
            log.error("MCP 出站目标 [{}] 连接失败：{}", target.getName(), e.getMessage());
        }
    }

    @PreDestroy
    void destroy() {
        for (var entry : connections.entrySet()) {
            log.info("MCP 出站目标 [{}] 关闭", entry.getKey());
        }
        connections.clear();
        remoteTools.clear();
    }

    /** 获取所有出站连接状态（管理 API） */
    public List<Map<String, Object>> status() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (var entry : connections.entrySet()) {
            ConnectionState cs = entry.getValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", cs.target.getName());
            m.put("url", cs.target.getUrl());
            m.put("status", cs.status);
            m.put("remoteToolCount", cs.remoteToolCount);
            m.put("enabled", cs.target.isEnabled());
            result.add(m);
        }
        return result;
    }

    /** 获取某连接的远端工具（W11 真实连接后才有数据） */
    public List<ToolCallback> getRemoteTools(String targetName) {
        return remoteTools.getOrDefault(targetName, Collections.emptyList());
    }

    private static final class ConnectionState {
        final McpOutboundProperties.OutboundTarget target;
        final String status;
        final int remoteToolCount;

        ConnectionState(McpOutboundProperties.OutboundTarget target, String status, int remoteToolCount) {
            this.target = target;
            this.status = status;
            this.remoteToolCount = remoteToolCount;
        }
    }
}
