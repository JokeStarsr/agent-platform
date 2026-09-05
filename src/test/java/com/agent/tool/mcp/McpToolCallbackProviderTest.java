package com.agent.tool.mcp;

import com.agent.tool.ToolEngineService;
import com.agent.tool.ToolMeta;
import com.agent.tool.ToolPermission;
import com.agent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpToolCallbackProviderTest {

    @Test
    void getToolCallbacks_buildsOnePerRegisteredTool() {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.listTools()).thenReturn(List.of(
                new ToolMeta("policy_query", "查政策", "{\"type\":\"object\"}", ToolPermission.READ, 30_000),
                new ToolMeta("book_order", "下单", "{\"type\":\"object\"}", ToolPermission.WRITE, 30_000)));

        McpToolCallbackProvider provider = new McpToolCallbackProvider(
                mock(ToolEngineService.class), registry, mock(McpAuthorizationService.class));

        var cbs = provider.getToolCallbacks();
        assertEquals(2, cbs.length);
        assertEquals("policy_query", cbs[0].getToolDefinition().name());
        assertEquals("book_order", cbs[1].getToolDefinition().name());
        assertTrue(cbs[0] instanceof AgentToolCallback);
    }
}
