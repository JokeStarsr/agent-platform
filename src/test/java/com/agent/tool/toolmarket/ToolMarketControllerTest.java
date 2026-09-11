package com.agent.tool.toolmarket;

import com.agent.data.openplatform.ApiKeyRepository;
import com.agent.data.openplatform.TenantQuotaRepository;
import com.agent.data.tokenmeter.TokenUsageDailyRepository;
import com.agent.data.toolmarket.ToolCatalogRepository;
import com.agent.data.toolmarket.ToolCatalogRepository.CatalogRow;
import com.agent.data.toolmarket.ToolStatsRepository;
import com.agent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ToolMarketController HTTP 契约测试（docs/design/architecture/20260905-tool-marketplace.md §7.1）
 */
@WebMvcTest(ToolMarketController.class)
@org.springframework.test.context.TestPropertySource(properties = "app.security.injection-filter=false")
class ToolMarketControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ToolMarketService service;

    @MockBean
    private ToolCatalogRepository repo;

    @MockBean
    private ToolStatsRepository statsRepo;

    @MockBean
    private ToolRegistry toolRegistry;

    // W17: Mock OpenApiFilter dependencies
    @MockBean
    private ApiKeyRepository apiKeyRepository;

    @MockBean
    private TenantQuotaRepository tenantQuotaRepository;

    @MockBean
    private TokenUsageDailyRepository tokenUsageDailyRepository;

    private CatalogRow row(long id, String name, String status) {
        return new CatalogRow(id, name, 1, name, "desc", "data", "{}", "READ",
                "BUILTIN", null, null, status, true, "platform", "[]", null, null);
    }

    @Test
    void list_returnsPagedOk() throws Exception {
        when(repo.count(null, null, null)).thenReturn(2L);
        when(repo.list(isNull(), isNull(), isNull(), eq(20), anyInt()))
                .thenReturn(List.of(row(1, "compare_flight", "PUBLISHED"), row(2, "book_order", "PUBLISHED")));

        mockMvc.perform(get("/api/tool-market").header("X-Tenant-Id", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)))
                .andExpect(jsonPath("$.data.total", is(2)))
                .andExpect(jsonPath("$.data.items", hasSize(2)))
                .andExpect(jsonPath("$.data.items[0].toolName", is("compare_flight")));
    }

    @Test
    void detail_returnsCurrentAndVersions() throws Exception {
        when(service.findByCurrent("compare_flight")).thenReturn(
                java.util.Optional.of(row(1, "compare_flight", "PUBLISHED")));
        when(service.listVersions("compare_flight")).thenReturn(
                List.of(row(2, "compare_flight", "DRAFT"), row(1, "compare_flight", "PUBLISHED")));

        mockMvc.perform(get("/api/tool-market/compare_flight").header("X-Tenant-Id", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)))
                .andExpect(jsonPath("$.data.current.toolName", is("compare_flight")))
                .andExpect(jsonPath("$.data.versions", hasSize(2)));
    }

    @Test
    void register_createsDraft() throws Exception {
        when(service.register(any(), any())).thenReturn(row(10, "email_notify", "DRAFT"));

        mockMvc.perform(post("/api/tool-market/register")
                        .header("X-Tenant-Id", "t1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"toolName":"email_notify","displayName":"邮件通知",
                                 "description":"发送电子邮件通知给用户，支持模板与收件人列表（演示工具）",
                                 "category":"communication","parameters":"{}","permission":"WRITE",
                                 "behavior":"MOCK",
                                 "testcases":[{"name":"发送","arguments":{},"expectCode":0}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)))
                .andExpect(jsonPath("$.data.status", is("DRAFT")))
                .andExpect(jsonPath("$.data.toolName", is("email_notify")));

        verify(service).register(any(), any());
    }

    @Test
    void publish_callsService() throws Exception {
        when(service.publish(eq(1L), isNull())).thenReturn(row(1, "report_daily", "PUBLISHED"));

        mockMvc.perform(post("/api/tool-market/1/publish").header("X-Tenant-Id", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)))
                .andExpect(jsonPath("$.data.status", is("PUBLISHED")));

        verify(service).publish(1L, null);
    }

    @Test
    void offShelf_callsService() throws Exception {
        mockMvc.perform(post("/api/tool-market/1/off-shelf").header("X-Tenant-Id", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)));

        verify(service).offShelf(1L);
    }

    @Test
    void stats_returnsAggregated() throws Exception {
        when(statsRepo.byTenant("default", 7)).thenReturn(List.of(
                new ToolStatsRepository.ToolStat("compare_flight", 12, 11, 1, 85.5, 0),
                new ToolStatsRepository.ToolStat("book_order", 3, 3, 0, 120.0, 1)));

        mockMvc.perform(get("/api/tool-market/stats").header("X-Tenant-Id", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)))
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].toolName", is("compare_flight")))
                .andExpect(jsonPath("$.data[0].total", is(12)))
                .andExpect(jsonPath("$.data[1].replayCount", is(1)));

        verify(statsRepo).byTenant("default", 7);
    }
}