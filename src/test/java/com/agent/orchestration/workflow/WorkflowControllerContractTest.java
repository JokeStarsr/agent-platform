package com.agent.orchestration.workflow;

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
 * WorkflowController HTTP 契约测试（docs/design/api/20260902-admin-pages.md §2.3）
 * 纯 MockMvc，验证：GET /api/workflow/instances 分页结构、status 筛选透传、租户头缺省、/flows 烟测。
 */
@WebMvcTest(WorkflowController.class)
class WorkflowControllerContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private WorkflowService workflow;

    @MockBean
    private WorkflowFlows flows;

    // W17: Mock OpenApiFilter dependencies
    @MockBean
    private ApiKeyRepository apiKeyRepository;

    @MockBean
    private TenantQuotaRepository tenantQuotaRepository;

    @MockBean
    private TokenUsageDailyRepository tokenUsageDailyRepository;

    private static Map<String, Object> instItem(long instanceId, String status) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("instanceId", instanceId);
        m.put("appId", "TR_BOOKING");
        m.put("flowId", "trip_booking");
        m.put("status", status);
        m.put("errorMsg", null);
        m.put("createdAt", "2026-09-02T00:00:00Z");
        m.put("finishedAt", null);
        return m;
    }

    @Test
    void list_缺X_Tenant_Id_回退default租户_返回空分页() throws Exception {
        when(workflow.listInstances(eq("default"), eq(1), eq(20), isNull()))
                .thenReturn(PageResult.of(1, 20, 0, List.of()));

        mockMvc.perform(get("/api/workflow/instances"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)))
                .andExpect(jsonPath("$.data.page", is(1)))
                .andExpect(jsonPath("$.data.size", is(20)))
                .andExpect(jsonPath("$.data.total", is(0)))
                .andExpect(jsonPath("$.data.totalPages", is(0)))
                .andExpect(jsonPath("$.data.items", hasSize(0)));
    }

    @Test
    void list_返回实例列表_分页结构与条目字段完整() throws Exception {
        when(workflow.listInstances(eq("tenant-x"), eq(1), eq(20), isNull()))
                .thenReturn(PageResult.of(1, 20, 1, List.of(instItem(12, "RUNNING"))));

        mockMvc.perform(get("/api/workflow/instances").header("X-Tenant-Id", "tenant-x"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total", is(1)))
                .andExpect(jsonPath("$.data.totalPages", is(1)))
                .andExpect(jsonPath("$.data.items[0].instanceId", is(12)))
                .andExpect(jsonPath("$.data.items[0].flowId", is("trip_booking")))
                .andExpect(jsonPath("$.data.items[0].status", is("RUNNING")))
                .andExpect(jsonPath("$.data.items[0].appId", is("TR_BOOKING")));
    }

    @Test
    void list_status筛选_透传Service() throws Exception {
        when(workflow.listInstances(eq("tenant-x"), eq(3), eq(10), eq("FAILED")))
                .thenReturn(PageResult.of(3, 10, 1, List.of(instItem(5, "FAILED"))));

        mockMvc.perform(get("/api/workflow/instances")
                        .header("X-Tenant-Id", "tenant-x")
                        .param("page", "3")
                        .param("size", "10")
                        .param("status", "FAILED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)));

        verify(workflow).listInstances(eq("tenant-x"), eq(3), eq(10), eq("FAILED"));
    }

    @Test
    void flows_内置流程目录返回trip_booking() throws Exception {
        mockMvc.perform(get("/api/workflow/flows"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)))
                .andExpect(jsonPath("$.data[0]", is("trip_booking")));
    }

    @Test
    void list_服务抛业务异常_统一Result错误包装() throws Exception {
        when(workflow.listInstances(anyString(), anyInt(), anyInt(), any()))
                .thenThrow(new com.agent.common.BizException(5002, "实例列表查询失败"));

        mockMvc.perform(get("/api/workflow/instances").header("X-Tenant-Id", "tenant-x"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(5002)))
                .andExpect(jsonPath("$.message", is("实例列表查询失败")));
    }
}