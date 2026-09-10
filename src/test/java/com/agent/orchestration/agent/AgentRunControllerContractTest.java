package com.agent.orchestration.agent;

import com.agent.common.PageResult;
import com.agent.data.openplatform.ApiKeyRepository;
import com.agent.data.openplatform.TenantQuotaRepository;
import com.agent.data.tokenmeter.TokenUsageDailyRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AgentRunController HTTP 契约测试（docs/design/api/20260902-admin-pages.md §2.2）
 * 纯 MockMvc，不依赖真实 DB/LLM，验证：GET /api/agent/runs 分页结构、status 筛选透传、租户头缺省。
 */
@WebMvcTest(AgentRunController.class)
class AgentRunControllerContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AgentRuntimeService agentRuntime;

    // W17: Mock OpenApiFilter dependencies
    @MockBean
    private ApiKeyRepository apiKeyRepository;

    @MockBean
    private TenantQuotaRepository tenantQuotaRepository;

    @MockBean
    private TokenUsageDailyRepository tokenUsageDailyRepository;

    private static Map<String, Object> runItem(long runId, String status) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runId", runId);
        m.put("appId", "CS_AGENT");
        m.put("task", "查询订单");
        m.put("status", status);
        m.put("maxSteps", 10);
        m.put("stepsDone", 3);
        m.put("tokensUsed", 1500);
        m.put("createdAt", "2026-09-02T00:00:00Z");
        m.put("finishedAt", null);
        return m;
    }

    @Test
    void list_缺X_Tenant_Id_回退default租户_返回空分页() throws Exception {
        when(agentRuntime.listRuns(eq("default"), eq(1), eq(20), isNull()))
                .thenReturn(PageResult.of(1, 20, 0, List.of()));

        mockMvc.perform(get("/api/agent/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)))
                .andExpect(jsonPath("$.data.page", is(1)))
                .andExpect(jsonPath("$.data.size", is(20)))
                .andExpect(jsonPath("$.data.total", is(0)))
                .andExpect(jsonPath("$.data.totalPages", is(0)))
                .andExpect(jsonPath("$.data.items", hasSize(0)));
    }

    @Test
    void list_返回运行列表_分页结构与条目字段完整() throws Exception {
        when(agentRuntime.listRuns(eq("tenant-x"), eq(1), eq(20), isNull()))
                .thenReturn(PageResult.of(1, 20, 2, List.of(runItem(9, "COMPLETED"), runItem(8, "RUNNING"))));

        mockMvc.perform(get("/api/agent/runs").header("X-Tenant-Id", "tenant-x"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total", is(2)))
                .andExpect(jsonPath("$.data.totalPages", is(1)))
                .andExpect(jsonPath("$.data.items", hasSize(2)))
                .andExpect(jsonPath("$.data.items[0].runId", is(9)))
                .andExpect(jsonPath("$.data.items[0].appId", is("CS_AGENT")))
                .andExpect(jsonPath("$.data.items[0].task", is("查询订单")))
                .andExpect(jsonPath("$.data.items[0].status", is("COMPLETED")))
                .andExpect(jsonPath("$.data.items[0].maxSteps", is(10)))
                .andExpect(jsonPath("$.data.items[0].tokensUsed", is(1500)))
                .andExpect(jsonPath("$.data.items[1].status", is("RUNNING")));
    }

    @Test
    void list_status筛选_透传Service() throws Exception {
        when(agentRuntime.listRuns(eq("tenant-x"), eq(2), eq(50), eq("WAITING_APPROVAL")))
                .thenReturn(PageResult.of(2, 50, 1, List.of(runItem(7, "WAITING_APPROVAL"))));

        mockMvc.perform(get("/api/agent/runs")
                        .header("X-Tenant-Id", "tenant-x")
                        .param("page", "2")
                        .param("size", "50")
                        .param("status", "WAITING_APPROVAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)))
                .andExpect(jsonPath("$.data.items[0].runId", is(7)));

        verify(agentRuntime).listRuns(eq("tenant-x"), eq(2), eq(50), eq("WAITING_APPROVAL"));
    }

    @Test
    void list_服务抛业务异常_统一Result错误包装() throws Exception {
        when(agentRuntime.listRuns(anyString(), anyInt(), anyInt(), any()))
                .thenThrow(new com.agent.common.BizException(5001, "列表查询失败"));

        mockMvc.perform(get("/api/agent/runs").header("X-Tenant-Id", "tenant-x"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(5001)))
                .andExpect(jsonPath("$.message", is("列表查询失败")));
    }
}