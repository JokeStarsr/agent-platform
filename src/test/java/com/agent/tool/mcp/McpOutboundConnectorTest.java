package com.agent.tool.mcp;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class McpOutboundConnectorTest {

    @Test
    void init_disabledTarget_registersDisabled() {
        McpOutboundProperties props = new McpOutboundProperties();
        var target = new McpOutboundProperties.OutboundTarget();
        target.setName("disabled-tools");
        target.setUrl("http://localhost:1/mcp/sse");
        target.setEnabled(false);
        props.setTargets(List.of(target));

        McpOutboundConnector connector = new McpOutboundConnector(props, mock(McpAuditService.class));
        connector.init();

        List<Map<String, Object>> status = connector.status();
        assertEquals(1, status.size());
        assertEquals("DISABLED", status.get(0).get("status"));
        connector.destroy();
    }

    @Test
    void init_enabledUnreachableTarget_registersError() {
        // 连不上的外部地址 → ERROR 状态（真实连接尝试失败兜底，不再 SKELETON）
        McpOutboundProperties props = new McpOutboundProperties();
        var target = new McpOutboundProperties.OutboundTarget();
        target.setName("unreachable-tools");
        target.setUrl("http://localhost:1/mcp/sse"); // 端口 1 必拒
        target.setEnabled(true);
        target.setConnectTimeoutMs(500);
        props.setTargets(List.of(target));

        McpOutboundConnector connector = new McpOutboundConnector(props, mock(McpAuditService.class));
        connector.init();

        List<Map<String, Object>> status = connector.status();
        assertEquals(1, status.size());
        assertEquals("ERROR", status.get(0).get("status"));
        assertEquals(0, status.get(0).get("remoteToolCount"));
        assertTrue(connector.getRemoteTools("unreachable-tools").isEmpty());
        connector.destroy();
    }

    @Test
    void init_noTargets_emptyStatus() {
        McpOutboundProperties props = new McpOutboundProperties();
        McpOutboundConnector connector = new McpOutboundConnector(props, mock(McpAuditService.class));
        connector.init();

        assertTrue(connector.status().isEmpty());
        assertTrue(connector.getRemoteTools("any").isEmpty());
        connector.destroy();
    }
}