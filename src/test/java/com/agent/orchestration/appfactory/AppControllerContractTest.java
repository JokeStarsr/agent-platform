package com.agent.orchestration.appfactory;

import com.agent.common.BizException;
import com.agent.data.application.AppRepository;
import com.agent.data.application.AppRepository.AppRow;
import com.agent.data.openplatform.ApiKeyRepository;
import com.agent.data.openplatform.TenantQuotaRepository;
import com.agent.data.tokenmeter.TokenUsageDailyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AppController HTTP 契约测试（docs/design/api/20260902-app-factory.md §2.3）
 * 纯 MockMvc，验证：列表分页、详情、创建校验、重复 409、启停状态流、租户头缺省。
 */
@WebMvcTest(AppController.class)
class AppControllerContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AppRepository repo;

    @MockBean
    private AppRegistry registry;

    @MockBean
    private AppValidator validator;

    // W17: Mock OpenApiFilter dependencies
    @MockBean
    private ApiKeyRepository apiKeyRepository;

    @MockBean
    private TenantQuotaRepository tenantQuotaRepository;

    @MockBean
    private TokenUsageDailyRepository tokenUsageDailyRepository;

    private static final String CFG = """
            {"role":{"name":"客服"},"prompt":{"system":"你是客服"},
             "quota":{"maxSteps":10,"tokenBudget":32000,"timeoutMs":300000,"loopThreshold":3,"maxConcurrency":5}}
            """;

    private AppRow row(long id, String status, String name) {
        return new AppRow(id, "tenant-x", "cs_customer_service", name, status, CFG, 1,
                Instant.parse("2026-09-02T00:00:00Z"), Instant.parse("2026-09-02T00:00:00Z"));
    }

    @BeforeEach
    void setUp() {
        when(repo.findByAppId("tenant-x", "cs_customer_service"))
                .thenReturn(Optional.of(row(1, "ENABLED", "企业客服")));
        doNothing().when(validator).validate(anyString());
    }

    /* ---------- GET /api/apps 列表 ---------- */

    @Test
    void list_返回分页结构与应用字段() throws Exception {
        when(repo.countVisible("tenant-x")).thenReturn(2L);
        when(repo.pageVisible("tenant-x", 1, 20)).thenReturn(List.of(row(1, "ENABLED", "企业客服")));

        mockMvc.perform(get("/api/apps").header("X-Tenant-Id", "tenant-x"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)))
                .andExpect(jsonPath("$.data.total", is(2)))
                .andExpect(jsonPath("$.data.items[0].appId", is("cs_customer_service")))
                .andExpect(jsonPath("$.data.items[0].status", is("ENABLED")))
                .andExpect(jsonPath("$.data.items[0].version", is(1)));
    }

    @Test
    void list_缺X_Tenant_Id_回退default租户() throws Exception {
        when(repo.countVisible("default")).thenReturn(0L);
        when(repo.pageVisible("default", 1, 20)).thenReturn(List.of());

        mockMvc.perform(get("/api/apps"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)))
                .andExpect(jsonPath("$.data.items", hasSize(0)));
        verify(repo).countVisible("default");
    }

    /* ---------- GET /api/apps/{appId} 详情 ---------- */

    @Test
    void detail_返回应用详情() throws Exception {
        mockMvc.perform(get("/api/apps/cs_customer_service").header("X-Tenant-Id", "tenant-x"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)))
                .andExpect(jsonPath("$.data.appId", is("cs_customer_service")))
                .andExpect(jsonPath("$.data.status", is("ENABLED")))
                .andExpect(jsonPath("$.data.configJson", containsString("你是客服")));
    }

    @Test
    void detail_应用不存在_返回404() throws Exception {
        when(repo.findByAppId("tenant-x", "ghost")).thenReturn(Optional.empty());
        mockMvc.perform(get("/api/apps/ghost").header("X-Tenant-Id", "tenant-x"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(404)));
    }

    /* ---------- POST /api/apps 创建 ---------- */

    @Test
    void create_合法配置_返回CREATED() throws Exception {
        when(repo.findByAppId("tenant-x", "it_ops")).thenReturn(Optional.empty());
        when(repo.create(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(row(2, "CREATED", "IT 运维"));

        mockMvc.perform(post("/api/apps").header("X-Tenant-Id", "tenant-x")
                        .contentType("application/json")
                        .content("{\"appId\":\"it_ops\",\"name\":\"IT 运维\",\"configJson\":" + toJsonString() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)))
                .andExpect(jsonPath("$.data.status", is("CREATED")));
        verify(registry).refresh("tenant-x", "it_ops");
    }

    @Test
    void create_重复appId_返回409() throws Exception {
        when(repo.findByAppId("tenant-x", "cs_customer_service")).thenReturn(Optional.of(row(1, "ENABLED", "")));

        mockMvc.perform(post("/api/apps").header("X-Tenant-Id", "tenant-x")
                        .contentType("application/json")
                        .content("{\"appId\":\"cs_customer_service\",\"name\":\"x\",\"configJson\":\"{}\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(409)));
        verify(repo, never()).create(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void create_配置校验失败_返回400() throws Exception {
        when(repo.findByAppId("tenant-x", "it_ops")).thenReturn(Optional.empty());
        doThrow(new BizException(400, "应用配置校验失败: quota 必填")).when(validator).validate(anyString());

        mockMvc.perform(post("/api/apps").header("X-Tenant-Id", "tenant-x")
                        .contentType("application/json")
                        .content("{\"appId\":\"it_ops\",\"name\":\"IT 运维\",\"configJson\":\"{\\\"quota\\\":{}}\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(400)))
                .andExpect(jsonPath("$.message", containsString("quota")));
    }

    @Test
    void create_缺appId_返回400校验错误() throws Exception {
        mockMvc.perform(post("/api/apps").header("X-Tenant-Id", "tenant-x")
                        .contentType("application/json")
                        .content("{\"name\":\"x\",\"configJson\":\"{}\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is(400)));
    }

    /* ---------- POST /api/apps/{appId}/start|suspend 启停 ---------- */

    @Test
    void start_从SUSPENDED启用() throws Exception {
        when(repo.findByAppId("tenant-x", "it_ops")).thenReturn(
                Optional.of(row(3, "SUSPENDED", "IT")),
                Optional.of(row(3, "ENABLED", "IT")));

        mockMvc.perform(post("/api/apps/it_ops/start").header("X-Tenant-Id", "tenant-x"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)))
                .andExpect(jsonPath("$.data.status", is("ENABLED")));
        verify(repo).updateStatus("tenant-x", "it_ops", "ENABLED");
    }

    @Test
    void start_已启用_返回409() throws Exception {
        mockMvc.perform(post("/api/apps/cs_customer_service/start").header("X-Tenant-Id", "tenant-x"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(409)));
    }

    @Test
    void start_校验失败_返回400() throws Exception {
        when(repo.findByAppId("tenant-x", "it_ops")).thenReturn(Optional.of(row(3, "SUSPENDED", "IT")));
        doThrow(new BizException(400, "应用配置校验失败: threshold 非法")).when(validator).validate(anyString());

        mockMvc.perform(post("/api/apps/it_ops/start").header("X-Tenant-Id", "tenant-x"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(400)));
        verify(repo, never()).updateStatus("tenant-x", "it_ops", "ENABLED");
    }

    @Test
    void suspend_停用ENABLED应用() throws Exception {
        when(repo.findByAppId("tenant-x", "cs_customer_service"))
                .thenReturn(Optional.of(row(1, "ENABLED", "企业客服")),
                        Optional.of(row(1, "SUSPENDED", "企业客服")));

        mockMvc.perform(post("/api/apps/cs_customer_service/suspend").header("X-Tenant-Id", "tenant-x"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("SUSPENDED")));
        verify(repo).updateStatus("tenant-x", "cs_customer_service", "SUSPENDED");
    }

    @Test
    void suspend_非ENABLED_返回409() throws Exception {
        when(repo.findByAppId("tenant-x", "it_ops")).thenReturn(Optional.of(row(3, "CREATED", "IT")));
        mockMvc.perform(post("/api/apps/it_ops/suspend").header("X-Tenant-Id", "tenant-x"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(409)));
    }

    private String toJsonString() {
        // Jackson 文本块换行转义：契约测试直接传 JSON 字符串字面量
        return "\"" + CFG.replace("\n", "").replace("\"", "\\\"") + "\"";
    }
}