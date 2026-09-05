package com.agent.tool.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * L5 MCP 网关：出站连接配置（docs/design/architecture/20260904-mcp-gateway.md §4）。
 * <p>每个 OutboundTarget 指向一个外部 MCP Server，平台内 Agent 可消费远端工具。</p>
 * <pre>
 * agent-platform:
 *   mcp:
 *     outbound:
 *       targets:
 *         - name: external-tools
 *           url: http://external-mcp-server:8080/mcp/sse
 *           enabled: true
 * </pre>
 */
@ConfigurationProperties(prefix = "agent-platform.mcp.outbound")
public class McpOutboundProperties {

    private List<OutboundTarget> targets = new ArrayList<>();

    public List<OutboundTarget> getTargets() {
        return targets;
    }

    public void setTargets(List<OutboundTarget> targets) {
        this.targets = targets == null ? new ArrayList<>() : targets;
    }

    public static class OutboundTarget {
        /** 连接器名称（标识用） */
        private String name;
        /** 外部 MCP Server SSE 端点 URL */
        private String url;
        /** 是否启用（默认 true） */
        private boolean enabled = true;
        /** 连接超时（毫秒，默认 10s） */
        private long connectTimeoutMs = 10_000;
        /** 请求超时（毫秒，默认 30s） */
        private long requestTimeoutMs = 30_000;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public long getConnectTimeoutMs() { return connectTimeoutMs; }
        public void setConnectTimeoutMs(long connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
        public long getRequestTimeoutMs() { return requestTimeoutMs; }
        public void setRequestTimeoutMs(long requestTimeoutMs) { this.requestTimeoutMs = requestTimeoutMs; }
    }
}
