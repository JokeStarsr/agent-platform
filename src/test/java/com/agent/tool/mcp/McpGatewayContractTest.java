package com.agent.tool.mcp;

import com.agent.data.toolgrant.ToolGrantRepository;
import com.agent.tool.ToolEngineService;
import com.agent.tool.ToolEngineService.InvokeRequest;
import com.agent.tool.ToolEngineService.ToolInvokeResult;
import com.agent.tool.ToolMeta;
import com.agent.tool.ToolPermission;
import com.agent.tool.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MCP 网关 WireMock 契约测试（docs/design/architecture/20260904-mcp-gateway.md §7.4）。
 * <p>分两部分验证 MCP 协议契约：</p>
 * <ol>
 *   <li><b>入站适配器契约</b>（直接调用，不需 SSE）：验证 AgentToolCallback 把 ToolMeta→MCP ToolDefinition
 *       映射、参数透传、幂等键提取、错误码映射（§7.3）</li>
 *   <li><b>WireMock 端点模拟</b>：模拟 MCP Server 端点的 JSON-RPC 请求/响应格式，验证协议结构正确
 *       （为 W11 出站连接测试打基础）</li>
 * </ol>
 * <p>完整 SSE 传输协议端到端测试留 {@code McpE2eIT}（@Tag("integration")，W11 真实 MCP Client 接入后补充）。</p>
 */
class McpGatewayContractTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .configureStaticDsl(true)
            .build();

    private static final ObjectMapper JSON = new ObjectMapper();

    private ToolEngineService toolEngine;
    private McpAuthorizationService authorization;
    private ToolGrantRepository grantRepo;
    private ToolRegistry toolRegistry;

    @BeforeEach
    void setUp() {
        toolEngine = mock(ToolEngineService.class);
        grantRepo = mock(ToolGrantRepository.class);
        toolRegistry = mock(ToolRegistry.class);
        authorization = new McpAuthorizationService(grantRepo);
    }

    // ==================== §8 工具定义映射契约（零重写适配器） ====================

    @Test
    void toolCallback_mapsToolMetaToMcpToolDefinition() {
        ToolMeta meta = new ToolMeta("compare_flight", "按城市与天数比对可选航班",
                "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}},\"required\":[\"city\"]}",
                ToolPermission.READ, 30_000);

        AgentToolCallback cb = new AgentToolCallback(toolEngine, authorization, meta);
        var def = cb.getToolDefinition();

        assertEquals("compare_flight", def.name());
        assertEquals("按城市与天数比对可选航班", def.description());
        assertTrue(def.inputSchema().contains("\"type\":\"object\""));
        assertTrue(def.inputSchema().contains("city"));
    }

    @Test
    void toolCallback_readTool_forwardsToEngineWithCorrectArgs() {
        ToolMeta meta = new ToolMeta("policy_query", "查政策",
                "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}}",
                ToolPermission.READ, 30_000);
        McpTenantContext.set("test-tenant");
        when(grantRepo.isAllowed("test-tenant", "policy_query")).thenReturn(true);
        when(toolEngine.invoke(eq("test-tenant"), eq("mcp-gateway"), any(InvokeRequest.class)))
                .thenReturn(ToolInvokeResult.ok(Map.of("economy", "Y", "maxHotelStar", 4)));

        AgentToolCallback cb = new AgentToolCallback(toolEngine, authorization, meta);
        String result = cb.call("{\"city\":\"北京\"}");

        assertTrue(result.contains("economy"));
        verify(toolEngine).invoke(eq("test-tenant"), eq("mcp-gateway"),
                argThat(req -> "policy_query".equals(req.tool())
                        && "北京".equals(req.args().get("city"))
                        && req.idempotencyKey() == null)); // READ 不需幂等键
        McpTenantContext.clear();
    }

    @Test
    void toolCallback_writeTool_extractsAndForwardsIdempotencyKey() {
        ToolMeta meta = new ToolMeta("book_order", "下单",
                "{\"type\":\"object\",\"properties\":{\"plan\":{\"type\":\"object\"}}}",
                ToolPermission.WRITE, 30_000);
        McpTenantContext.set("test-tenant");
        when(grantRepo.isAllowed("test-tenant", "book_order")).thenReturn(true);
        when(toolEngine.invoke(anyString(), anyString(), any(InvokeRequest.class)))
                .thenReturn(ToolInvokeResult.ok(Map.of("orderId", "ORD-001")));

        AgentToolCallback cb = new AgentToolCallback(toolEngine, authorization, meta);
        cb.call("{\"plan\":{\"flight\":\"CA123\"},\"idempotencyKey\":\"t1:mcp:uuid-1\"}");

        // 幂等键从 args 提取并透传，args 中不再包含 idempotencyKey（§3.5）
        verify(toolEngine).invoke(anyString(), anyString(),
                argThat(req -> "t1:mcp:uuid-1".equals(req.idempotencyKey())
                        && !req.args().containsKey("idempotencyKey")));
        McpTenantContext.clear();
    }

    @Test
    void toolCallback_idempotentReplay_returnsCachedData() {
        // 幂等重放场景：ToolEngine 返回 replay 结果（data 非空时直接返回 data）
        ToolMeta meta = new ToolMeta("book_order", "下单", "{}", ToolPermission.WRITE, 30_000);
        McpTenantContext.set("t1");
        when(grantRepo.isAllowed("t1", "book_order")).thenReturn(true);
        when(toolEngine.invoke(anyString(), anyString(), any(InvokeRequest.class)))
                .thenReturn(ToolInvokeResult.replay(Map.of("orderId", "ORD-001")));

        AgentToolCallback cb = new AgentToolCallback(toolEngine, authorization, meta);
        String result = cb.call("{\"idempotencyKey\":\"t1:mcp:uuid-1\"}");

        // 重放时返回缓存数据（orderId 在返回中）
        assertTrue(result.contains("ORD-001"));
        McpTenantContext.clear();
    }

    // ==================== §7.3 错误码映射契约 ====================

    @Test
    void toolCallback_engineError_throwsMcpExecutionException() {
        ToolMeta meta = new ToolMeta("book_order", "下单", "{}", ToolPermission.WRITE, 30_000);
        McpTenantContext.set("t1");
        when(grantRepo.isAllowed("t1", "book_order")).thenReturn(true);
        when(toolEngine.invoke(anyString(), anyString(), any(InvokeRequest.class)))
                .thenThrow(new RuntimeException("POLICY_VIOLATION: 舱位超标"));

        AgentToolCallback cb = new AgentToolCallback(toolEngine, authorization, meta);
        McpToolExecutionException ex = assertThrows(McpToolExecutionException.class,
                () -> cb.call("{\"idempotencyKey\":\"k1\"}"));

        assertEquals("book_order", ex.tool());
        assertTrue(ex.detail().contains("POLICY_VIOLATION"));
        McpTenantContext.clear();
    }

    @Test
    void authorization_denied_throwsWithTenantInfo() {
        when(grantRepo.isAllowed("unauthorized-tenant", "book_order")).thenReturn(false);

        McpToolExecutionException ex = assertThrows(McpToolExecutionException.class,
                () -> authorization.checkAllowed("unauthorized-tenant", "book_order"));

        assertTrue(ex.detail().contains("unauthorized-tenant"));
        assertTrue(ex.detail().contains("book_order"));
    }

    @Test
    void authorization_dbError_conservativeDeny() {
        when(grantRepo.isAllowed("t1", "tool"))
                .thenThrow(new RuntimeException("Connection refused"));
        // DB 异常 → 保守拒绝（安全红线，§5）
        assertThrows(McpToolExecutionException.class,
                () -> authorization.checkAllowed("t1", "tool"));
    }

    // ==================== §7.2 管理 API 响应结构契约 ====================

    @Test
    void toolsApiResponse_matchesResultWrapperContract() throws Exception {
        ToolMeta meta = new ToolMeta("compare_flight", "比航班", "{}", ToolPermission.READ, 30_000);
        when(toolRegistry.listTools()).thenReturn(List.of(meta));

        McpAdminController controller = new McpAdminController(
                toolRegistry, grantRepo, mock(McpServerRegistry.class), mock(McpOutboundConnector.class));

        var result = controller.tools();
        String json = JSON.writeValueAsString(result);
        JsonNode node = JSON.readTree(json);

        assertEquals(0, node.get("code").asInt());
        assertTrue(node.get("data").isArray());
        assertEquals("compare_flight", node.get("data").get(0).get("name").asText());
        assertEquals("READ", node.get("data").get(0).get("permission").asText());
    }

    @Test
    void healthApiResponse_matchesContract() throws Exception {
        McpServerRegistry registry = mock(McpServerRegistry.class);
        when(registry.health()).thenReturn(Map.of(
                "status", "UP", "server", "agent-platform", "toolCount", 11, "uptimeMs", 5000L));

        McpAdminController controller = new McpAdminController(
                toolRegistry, grantRepo, registry, mock(McpOutboundConnector.class));

        var result = controller.health();
        String json = JSON.writeValueAsString(result);
        JsonNode node = JSON.readTree(json);

        assertEquals(0, node.get("code").asInt());
        assertEquals("UP", node.get("data").get("status").asText());
        assertEquals("agent-platform", node.get("data").get("server").asText());
    }

    // ==================== WireMock JSON-RPC 协议结构验证 ====================

    private String wmBaseUrl() {
        return "http://localhost:" + wm.getPort();
    }

    @Test
    void wireMock_toolsListResponse_matchesMcpJsonRpcFormat() throws Exception {
        // 模拟 MCP Server 对 tools/list 的标准 JSON-RPC 响应
        stubFor(post(urlEqualTo("/mcp/message"))
                .withRequestBody(matchingJsonPath("$.method", equalTo("tools/list")))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":["
                                + "{\"name\":\"compare_flight\",\"description\":\"比航班\","
                                + "\"inputSchema\":{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}}}"
                                + "]}}")));

        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> resp = client.send(HttpRequest.newBuilder()
                .uri(URI.create(wmBaseUrl() + "/mcp/message"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
        JsonNode node = JSON.readTree(resp.body());
        assertEquals("2.0", node.get("jsonrpc").asText());
        assertEquals(1, node.get("id").asInt());
        assertTrue(node.has("result"));
        JsonNode tools = node.get("result").get("tools");
        assertTrue(tools.isArray());
        assertEquals("compare_flight", tools.get(0).get("name").asText());
        assertTrue(tools.get(0).has("inputSchema"));
    }

    @Test
    void wireMock_toolsCallResponse_matchesMcpContentFormat() throws Exception {
        // 模拟 MCP Server 对 tools/call 的标准响应（content[] 数组 + isError）
        stubFor(post(urlEqualTo("/mcp/message"))
                .withRequestBody(matchingJsonPath("$.method", equalTo("tools/call")))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":["
                                + "{\"type\":\"text\",\"text\":\"{\\\"economy\\\":\\\"Y\\\"}\"}"
                                + "],\"isError\":false}}")));

        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> resp = client.send(HttpRequest.newBuilder()
                .uri(URI.create(wmBaseUrl() + "/mcp/message"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                                + "\"params\":{\"name\":\"policy_query\",\"arguments\":{\"city\":\"北京\"}}}"))
                .build(), HttpResponse.BodyHandlers.ofString());

        JsonNode node = JSON.readTree(resp.body());
        assertEquals("2.0", node.get("jsonrpc").asText());
        JsonNode content = node.get("result").get("content");
        assertTrue(content.isArray());
        assertEquals("text", content.get(0).get("type").asText());
        assertFalse(node.get("result").get("isError").asBoolean());
    }

    @Test
    void wireMock_toolsCallError_matchesMcpErrorFormat() throws Exception {
        // 模拟 MCP Server 返回 JSON-RPC error（§7.3 错误码 -32003 = 工具未授权）
        stubFor(post(urlEqualTo("/mcp/message"))
                .withRequestBody(matchingJsonPath("$.method", equalTo("tools/call")))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"jsonrpc\":\"2.0\",\"id\":3,\"error\":{\"code\":-32003,\"message\":\"租户未被授权调用此工具\"}}")));

        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> resp = client.send(HttpRequest.newBuilder()
                .uri(URI.create(wmBaseUrl() + "/mcp/message"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
                                + "\"params\":{\"name\":\"pay_order\",\"arguments\":{}}}"))
                .build(), HttpResponse.BodyHandlers.ofString());

        JsonNode node = JSON.readTree(resp.body());
        assertTrue(node.has("error"));
        assertEquals(-32003, node.get("error").get("code").asInt());
        assertTrue(node.get("error").get("message").asText().contains("未被授权"));
    }
}
