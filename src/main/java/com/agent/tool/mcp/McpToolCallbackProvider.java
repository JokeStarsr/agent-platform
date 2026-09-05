package com.agent.tool.mcp;

import com.agent.tool.ToolEngineService;
import com.agent.tool.ToolRegistry;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * L5 MCP 网关：把 ToolRegistry 全部本地工具包装为 ToolCallback，供 spring-ai-mcp-server
 * 暴露为 MCP 工具（docs/design/architecture/20260904-mcp-gateway.md §3.2 / §8，零重写适配器）。
 */
@Component
public class McpToolCallbackProvider implements ToolCallbackProvider {

    private final ToolEngineService toolEngine;
    private final ToolRegistry toolRegistry;
    private final McpAuthorizationService authorization;

    public McpToolCallbackProvider(ToolEngineService toolEngine, ToolRegistry toolRegistry,
                                   McpAuthorizationService authorization) {
        this.toolEngine = toolEngine;
        this.toolRegistry = toolRegistry;
        this.authorization = authorization;
    }

    @Override
    public ToolCallback[] getToolCallbacks() {
        return toolRegistry.listTools().stream()
                .map(meta -> new AgentToolCallback(toolEngine, authorization, meta))
                .toArray(ToolCallback[]::new);
    }
}
