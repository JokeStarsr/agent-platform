package com.agent.tool.mcp;

/**
 * L5 MCP 网关：租户上下文（ThreadLocal）。
 * <p>McpAuthFilter 解析 X-Tenant-Id 后写入，AgentToolCallback 在 tools/call 执行时读取，
 * 使 MCP 调用具备租户上下文（授权判定 / 租户隔离 / 审计均依赖它）。
 * servlet 请求线程内同步执行，ThreadLocal 有效。</p>
 */
public final class McpTenantContext {

    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    private McpTenantContext() {
    }

    public static void set(String tenantId) {
        HOLDER.set(tenantId);
    }

    public static String get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
