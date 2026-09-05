package com.agent.tool.mcp;

import com.agent.tool.ToolMeta;
import com.agent.tool.ToolPermission;
import com.agent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpServerRegistryTest {

    @Test
    void health_withTools_returnsUp() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.listTools()).thenReturn(List.of(
                new ToolMeta("policy_query", "查政策", "{}", ToolPermission.READ, 30_000)));
        McpAuditService audit = mock(McpAuditService.class);
        McpServerRegistry registry = new McpServerRegistry(toolRegistry, audit);

        Map<String, Object> h = registry.health();
        assertEquals("UP", h.get("status"));
        assertEquals("agent-platform", h.get("server"));
        assertEquals(1, h.get("toolCount"));
        assertTrue((long) h.get("uptimeMs") >= 0);
    }

    @Test
    void health_noTools_returnsDegraded() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.listTools()).thenReturn(List.of());
        McpAuditService audit = mock(McpAuditService.class);
        McpServerRegistry registry = new McpServerRegistry(toolRegistry, audit);

        Map<String, Object> h = registry.health();
        assertEquals("DEGRADED", h.get("status"));
        assertEquals(0, h.get("toolCount"));
    }

    @Test
    void postConstruct_logsUpEvent() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.listTools()).thenReturn(List.of(
                new ToolMeta("t1", "d", "{}", ToolPermission.READ, 1000)));
        McpAuditService audit = mock(McpAuditService.class);
        McpServerRegistry registry = new McpServerRegistry(toolRegistry, audit);
        registry.onUp();
        verify(audit).logUpDown("up", 1);
    }
}
