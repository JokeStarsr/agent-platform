package com.agent.tool.mcp;

/**
 * L5 MCP 网关：工具执行失败（MCP server 会映射为 isError 响应，message 回给调用方）。
 */
public class McpToolExecutionException extends RuntimeException {

    private final String tool;
    private final String detail;

    public McpToolExecutionException(String tool, String detail, Throwable cause) {
        super(detail, cause);
        this.tool = tool;
        this.detail = detail;
    }

    public String tool() {
        return tool;
    }

    public String detail() {
        return detail;
    }
}
