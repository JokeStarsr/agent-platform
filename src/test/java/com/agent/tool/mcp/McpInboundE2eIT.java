package com.agent.tool.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP 入站端到端（真实 client → 平台 MCP Server，SSE 传输）。
 * <p>{@code @Tag("integration")} 默认不跑；需平台在 8082 运行后手动执行：<pre>
 * mvn test -Dtest=McpInboundE2eIT</pre>
 * 验证 initialize → tools/list 全链路（含 McpAuthFilter 租户鉴权）。</p>
 */
@Tag("integration")
class McpInboundE2eIT {

    public static final String BASE_URL =
            System.getenv().getOrDefault("MCP_SERVER_URL", "http://localhost:8082");

    private McpSyncClient client;

    private McpSyncClient connect() {
        // builder(baseUrl) 会默认追加 /sse，故 baseUrl 不带路径、SSE 端点显式给定
        HttpClientSseClientTransport transport =
                HttpClientSseClientTransport.builder(BASE_URL)
                        .sseEndpoint("/mcp/sse")
                        // 平台 MCP 入站需租户级鉴权（X-Tenant-Id + X-Api-Key）
                        .customizeRequest(req -> req.header("X-Tenant-Id", "default")
                                .header("X-Api-Key", "dev-key"))
                        .build();
        McpSyncClient c = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(15))
                .build();
        c.initialize();
        return c;
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    void connectAndListTools_againstRunningServer() {
        client = connect();
        List<McpSchema.Tool> tools = client.listTools().tools();
        assertNotNull(tools);
        assertTrue(tools.size() >= 10, "应暴露 ≥10 工具，实际 " + tools.size());
        System.out.println("MCP tools/list 返回 " + tools.size() + " 个工具："
                + tools.stream().map(McpSchema.Tool::name).limit(5).toList());
    }

    @Test
    void callReadTool_returnsResult() {
        client = connect();
        McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(
                "compare_flight",
                java.util.Map.of("city", "北京", "days", 3)));
        System.out.println("MCP callTool raw: isError=" + result.isError() + " content=" + result.content());
        assertNotNull(result);
        assertTrue(result.isError() == null || !result.isError(),
                "compare_flight（READ）应成功，isError=" + result.isError());
    }
}