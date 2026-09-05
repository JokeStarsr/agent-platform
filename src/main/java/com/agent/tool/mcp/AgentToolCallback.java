package com.agent.tool.mcp;

import com.agent.tool.ToolEngineService;
import com.agent.tool.ToolEngineService.InvokeRequest;
import com.agent.tool.ToolMeta;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * L5 MCP 网关：把单个本地工具（AgentTool 元数据 + ToolEngine 内核）桥接为 Spring AI ToolCallback，
 * 供 spring-ai-mcp-server 暴露为 MCP 工具。
 * <p>执行路径：MCP tools/call → 本类 call() → ToolEngineService.invoke()（复用幂等/Schema 校验/
 * 权限/超时）。租户取自 McpTenantContext（由 McpAuthFilter 写入），幂等键从参数中提取透传。</p>
 */
public class AgentToolCallback implements ToolCallback {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ToolEngineService toolEngine;
    private final McpAuthorizationService authorization;
    private final ToolMeta meta;
    private final ToolDefinition definition;

    public AgentToolCallback(ToolEngineService toolEngine, McpAuthorizationService authorization, ToolMeta meta) {
        this.toolEngine = toolEngine;
        this.authorization = authorization;
        this.meta = meta;
        this.definition = ToolDefinition.builder()
                .name(meta.name())
                .description(meta.description())
                .inputSchema(meta.parameters())
                .build();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public String call(String toolInput) {
        Map<String, Object> args = parseArgs(toolInput);
        String idempotencyKey = extractIdempotencyKey(args);
        String tenantId = McpTenantContext.get() == null ? "default" : McpTenantContext.get();
        // 租户级授权（deny-by-default）：未授权租户调用被拒（检查点）
        authorization.checkAllowed(tenantId, meta.name());
        try {
            ToolEngineService.ToolInvokeResult result =
                    toolEngine.invoke(tenantId, McpConstants.GATEWAY_APP_ID,
                            new InvokeRequest(meta.name(), args, idempotencyKey));
            return toJson(result.data() == null ? Map.of("_idempotentReplay", result.idempotentReplay())
                    : result.data());
        } catch (RuntimeException e) {
            // 让 MCP server 把异常映射为 isError 响应；message 回给调用方
            throw new McpToolExecutionException(meta.name(), e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), e);
        }
    }

    /** Spring AI MCP server 端 trails/call 走带 ToolContext 的重载，委托给 call(String)（修复 "Tool context is not supported!"） */
    @Override
    public String call(String toolInput, ToolContext context) {
        return call(toolInput);
    }

    /** 解析参数；idempotencyKey / _meta 属于网关控制字段，不透传给工具本体 */
    private Map<String, Object> parseArgs(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = JSON.readValue(toolInput, Map.class);
            return m == null ? new LinkedHashMap<>() : new LinkedHashMap<>(m);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    /** 写操作幂等键：优先顶层 idempotencyKey，其次 _meta.idempotencyKey；提取后从 args 移除 */
    private String extractIdempotencyKey(Map<String, Object> args) {
        String key = null;
        if (args.containsKey("idempotencyKey")) {
            key = String.valueOf(args.remove("idempotencyKey"));
        }
        Object metaObj = args.remove("_meta");
        if (key == null && metaObj instanceof Map<?, ?> mm && mm.get("idempotencyKey") != null) {
            key = String.valueOf(mm.get("idempotencyKey"));
        }
        return key;
    }

    private String toJson(Object v) {
        try {
            return JSON.writeValueAsString(v);
        } catch (Exception e) {
            return String.valueOf(v);
        }
    }
}
