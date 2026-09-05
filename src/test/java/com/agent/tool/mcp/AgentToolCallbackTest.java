package com.agent.tool.mcp;

import com.agent.tool.ToolEngineService;
import com.agent.tool.ToolEngineService.InvokeRequest;
import com.agent.tool.ToolEngineService.ToolInvokeResult;
import com.agent.tool.ToolMeta;
import com.agent.tool.ToolPermission;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentToolCallbackTest {

    private ToolEngineService toolEngine;
    private McpAuthorizationService authorization;
    private ToolMeta readMeta;

    @BeforeEach
    void setUp() {
        toolEngine = mock(ToolEngineService.class);
        authorization = mock(McpAuthorizationService.class);
        readMeta = new ToolMeta("query_recent_orders", "查询订单", "{\"type\":\"object\",\"properties\":{}}",
                ToolPermission.READ, 30_000);
        McpTenantContext.set("t1");
    }

    @AfterEach
    void tearDown() {
        McpTenantContext.clear();
    }

    @Test
    void call_readTool_forwardsToEngine() {
        when(toolEngine.invoke(eq("t1"), eq("mcp-gateway"), any()))
                .thenReturn(ToolInvokeResult.ok(Map.of("orders", "[]")));
        AgentToolCallback cb = new AgentToolCallback(toolEngine, authorization, readMeta);
        String out = cb.call("{\"userId\":\"u1\"}");
        assertEquals("{\"orders\":\"[]\"}", out);
        verify(toolEngine).invoke(eq("t1"), eq("mcp-gateway"),
                eq(new InvokeRequest("query_recent_orders", Map.of("userId", "u1"), null)));
    }

    @Test
    void call_writeTool_extractsIdempotencyKey() {
        ToolMeta writeMeta = new ToolMeta("send_coupon", "发券", "{\"type\":\"object\",\"properties\":{}}",
                ToolPermission.WRITE, 30_000);
        when(toolEngine.invoke(eq("t1"), eq("mcp-gateway"), any()))
                .thenReturn(ToolInvokeResult.ok(Map.of("ok", true)));
        AgentToolCallback cb = new AgentToolCallback(toolEngine, authorization, writeMeta);
        cb.call("{\"userId\":\"u1\",\"idempotencyKey\":\"k1\"}");
        verify(toolEngine).invoke(eq("t1"), eq("mcp-gateway"),
                eq(new InvokeRequest("send_coupon", Map.of("userId", "u1"), "k1")));
    }

    @Test
    void call_unauthorizedTenant_throws() {
        doThrow(new McpToolExecutionException("query_recent_orders", "租户 t1 未被授权", null))
                .when(authorization).checkAllowed("t1", "query_recent_orders");
        AgentToolCallback cb = new AgentToolCallback(toolEngine, authorization, readMeta);
        assertThrows(McpToolExecutionException.class, () -> cb.call("{}"));
    }

    @Test
    void getToolDefinition_mapsMeta() {
        AgentToolCallback cb = new AgentToolCallback(toolEngine, authorization, readMeta);
        assertEquals("query_recent_orders", cb.getToolDefinition().name());
        assertEquals("查询订单", cb.getToolDefinition().description());
        assertEquals(readMeta.parameters(), cb.getToolDefinition().inputSchema());
    }
}
