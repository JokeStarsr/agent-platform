package com.agent.tool.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * L5 MCP 网关：出站 MCP Client 连接器（docs/design/architecture/20260905-tool-marketplace.md §5）。
 * <p>对每个 enabled 的 OutboundTarget 建立真实 SSE 连接（HttpClientSseClientTransport →
 * McpSyncClient → initialize → listTools → SyncMcpToolCallbackProvider），远端工具包装为
 * ToolCallback 供平台内 Agent（AgentRuntime / Workflow）消费，并按 W11 目录登记策略同步进
 * t_tool_catalog（source=OUTBOUND_MCP）。</p>
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

    /** targetName → 底层客户端（关闭用） */
    private final Map<String, McpSyncClient> clients = new ConcurrentHashMap<>();

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
     * 真实连接外部 MCP Server（W11）。
     * SSE 传输 → 同步 client → initialize 握手 → listTools 发现远端工具 → ToolCallback。
     */
    void connectTarget(McpOutboundProperties.OutboundTarget target) {
        try {
            HttpClientSseClientTransport transport =
                    HttpClientSseClientTransport.builder(target.getUrl()).build();
            McpSyncClient client = McpClient.sync(transport)
                    .requestTimeout(Duration.ofMillis(target.getRequestTimeoutMs()))
                    .build();
            client.initialize();
            List<McpSchema.Tool> tools = client.listTools().tools();
            SyncMcpToolCallbackProvider provider = new SyncMcpToolCallbackProvider(client);
            List<ToolCallback> callbacks = new ArrayList<>(Arrays.asList(provider.getToolCallbacks()));
            remoteTools.put(target.getName(), callbacks);
            clients.put(target.getName(), client);
            connections.put(target.getName(), new ConnectionState(target, "CONNECTED", tools.size()));
            log.info("MCP 出站目标 [{}] 已连接（url={}，远端工具 {} 个）", target.getName(), target.getUrl(), tools.size());
            // TODO(W11 目录对接)：远端工具登记进 t_tool_catalog（source=OUTBOUND_MCP）随 ToolMarketService 扩展
        } catch (Exception e) {
            connections.put(target.getName(), new ConnectionState(target, "ERROR", 0));
            log.error("MCP 出站目标 [{}] 连接失败：url={} err={}", target.getName(), target.getUrl(), e.getMessage());
        }
    }

    /** 手动重连（管理 API 触发 + 失败后定时重试用） */
    public void reconnect(String targetName) {
        McpOutboundProperties.OutboundTarget target = properties.getTargets().stream()
                .filter(t -> targetName.equals(t.getName()))
                .findFirst().orElse(null);
        if (target == null) {
            return;
        }
        McpSyncClient old = clients.remove(targetName);
        if (old != null) {
            try { old.close(); } catch (Exception ignored) { }
        }
        connectTarget(target);
    }

    @PreDestroy
    void destroy() {
        for (var entry : clients.entrySet()) {
            try {
                entry.getValue().close();
                log.info("MCP 出站目标 [{}] 关闭", entry.getKey());
            } catch (Exception e) {
                log.warn("MCP 出站目标 [{}] 关闭异常：{}", entry.getKey(), e.getMessage());
            }
        }
        connections.clear();
        remoteTools.clear();
        clients.clear();
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

    /** 获取某连接的远端工具 */
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