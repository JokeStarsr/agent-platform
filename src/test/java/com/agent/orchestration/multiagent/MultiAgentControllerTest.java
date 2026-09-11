package com.agent.orchestration.multiagent;

import com.agent.data.multiagent.MultiAgentRunRepository;
import com.agent.data.openplatform.ApiKeyRepository;
import com.agent.data.openplatform.TenantQuotaRepository;
import com.agent.data.tokenmeter.TokenUsageDailyRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MultiAgentController 契约测试（docs/design/architecture/20260905-multi-agent.md §8）
 */
@WebMvcTest(MultiAgentController.class)
@org.springframework.test.context.TestPropertySource(properties = "app.security.injection-filter=false")
class MultiAgentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MultiAgentService service;

    @MockBean
    private MultiAgentRunRepository repo;

    @MockBean
    private SharedBlackboard blackboard;

    // W17: Mock OpenApiFilter dependencies
    @MockBean
    private ApiKeyRepository apiKeyRepository;

    @MockBean
    private TenantQuotaRepository tenantQuotaRepository;

    @MockBean
    private TokenUsageDailyRepository tokenUsageDailyRepository;

    @Test
    void submit_supervisor_returnsRootRunId() throws Exception {
        when(service.submit(eq("t1"), any())).thenReturn(1L);

        mockMvc.perform(post("/api/multi-agent/runs")
                        .header("X-Tenant-Id", "t1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topology\":\"supervisor\",\"task\":\"规划一次北京出差\",\"appId\":\"sv_delegator\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)))
                .andExpect(jsonPath("$.data.rootRunId", is(1)));

        verify(service).submit(eq("t1"), any());
    }

    @Test
    void list_returnsPagedOk() throws Exception {
        when(service.list("t1", 1, 20, null))
                .thenReturn(new com.agent.common.PageResult<>(1, 20, 1, java.util.List.of(Map.of(
                        "id", 1, "topology", "supervisor", "status", "COMPLETED"))));

        mockMvc.perform(get("/api/multi-agent/runs").header("X-Tenant-Id", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)))
                .andExpect(jsonPath("$.data.total", is(1)))
                .andExpect(jsonPath("$.data.items[0].topology", is("supervisor")));
    }

    @Test
    void detail_returnsOk() throws Exception {
        when(service.detail("t1", 1L))
                .thenReturn(Map.of("id", 1, "status", "COMPLETED", "finalAnswer", "方案"));

        mockMvc.perform(get("/api/multi-agent/runs/1").header("X-Tenant-Id", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)))
                .andExpect(jsonPath("$.data.status", is("COMPLETED")))
                .andExpect(jsonPath("$.data.finalAnswer", is("方案")));
    }

    @Test
    void board_returnsOk() throws Exception {
        mockMvc.perform(get("/api/multi-agent/runs/1/board").header("X-Tenant-Id", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)));

        verify(blackboard).read("t1", 1L);
    }
}