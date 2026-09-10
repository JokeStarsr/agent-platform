package com.agent.tool.mcp;

import com.agent.data.toolgrant.ToolGrantRepository;
import com.agent.data.toolgrant.ToolGrantRepository.GrantRow;
import com.agent.data.openplatform.ApiKeyRepository;
import com.agent.data.openplatform.TenantQuotaRepository;
import com.agent.data.tokenmeter.TokenUsageDailyRepository;
import com.agent.tool.ToolMeta;
import com.agent.tool.ToolPermission;
import com.agent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * McpAdminController HTTP 契约测试（docs/design/architecture/20260904-mcp-gateway.md §7.2）
 */
@WebMvcTest(McpAdminController.class)
class McpAdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ToolRegistry toolRegistry;

    @MockBean
    private ToolGrantRepository grantRepo;

    @MockBean
    private McpServerRegistry serverRegistry;

    @MockBean
    private McpOutboundConnector outbound;

    // W17: Mock OpenApiFilter dependencies
    @MockBean
    private ApiKeyRepository apiKeyRepository;

    @MockBean
    private TenantQuotaRepository tenantQuotaRepository;

    @MockBean
    private TokenUsageDailyRepository tokenUsageDailyRepository;

    @Test
    void tools_returnsRegisteredTools() throws Exception {
        when(toolRegistry.listTools()).thenReturn(List.of(
                new ToolMeta("policy_query", "查政策", "{}", ToolPermission.READ, 30_000),
                new ToolMeta("compare_flight", "比航班", "{}", ToolPermission.READ, 30_000)));

        mockMvc.perform(get("/api/mcp/tools").header("X-Tenant-Id", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)))
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].name", is("policy_query")))
                .andExpect(jsonPath("$.data[1].name", is("compare_flight")));
    }

    @Test
    void grants_returnsTenantGrants() throws Exception {
        when(grantRepo.listByTenant("t1")).thenReturn(List.of(
                new GrantRow(1, "t1", "compare_flight", "READ", true),
                new GrantRow(2, "t1", "book_order", "WRITE", true)));

        mockMvc.perform(get("/api/mcp/grants").param("tenantId", "t1").header("X-Tenant-Id", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)))
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].toolName", is("compare_flight")));
    }

    @Test
    void grant_authorizeTool() throws Exception {
        mockMvc.perform(post("/api/mcp/grants")
                        .header("X-Tenant-Id", "t1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"t1\",\"toolName\":\"cancel_order\",\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)));

        verify(grantRepo).setEnabled("t1", "cancel_order", true);
    }

    @Test
    void health_returnsStatus() throws Exception {
        when(serverRegistry.health()).thenReturn(Map.of(
                "status", "UP", "server", "agent-platform", "toolCount", 8, "uptimeMs", 12345L));

        mockMvc.perform(get("/api/mcp/health").header("X-Tenant-Id", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)))
                .andExpect(jsonPath("$.data.status", is("UP")))
                .andExpect(jsonPath("$.data.toolCount", is(8)));
    }

    @Test
    void outbound_returnsConnectorStatus() throws Exception {
        when(outbound.status()).thenReturn(List.of(
                Map.of("name", "ext-tools", "url", "http://ext:8080/mcp/sse",
                        "status", "SKELETON", "remoteToolCount", 0, "enabled", true)));

        mockMvc.perform(get("/api/mcp/outbound").header("X-Tenant-Id", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].name", is("ext-tools")))
                .andExpect(jsonPath("$.data[0].status", is("SKELETON")));
    }
}
