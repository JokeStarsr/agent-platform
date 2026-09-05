package com.agent.tool.mcp;

/**
 * L5 MCP 网关：常量（网关标识、端点路径、配置前缀）。
 */
public final class McpConstants {

    private McpConstants() {
    }

    /** MCP 入站统一使用的 appId（外部 Agent 不在 t_app 注册，授权走租户级 t_tool_grant） */
    public static final String GATEWAY_APP_ID = "mcp-gateway";

    /** MCP SSE 端点前缀（spring-ai-mcp-server webmvc 传输暴露路径，鉴权 Filter 拦截用） */
    public static final String ENDPOINT_PREFIX = "/mcp";

    /** 配置前缀 */
    public static final String CONFIG_PREFIX = "agent-platform.mcp";
}
