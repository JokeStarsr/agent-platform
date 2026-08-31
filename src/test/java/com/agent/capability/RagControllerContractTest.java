package com.agent.capability;

import com.agent.capability.RagService.RagResult;
import com.agent.common.BizException;
import com.agent.common.Result;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * RagController HTTP 契约测试（对应 docs/design/api/20260829-rag-controller.md）
 * 纯 MockMvc，不依赖真实向量库/LLM/审计切面，验证：路径、请求/响应结构、参数校验、
 * 租户头透传与默认值、异常统一包装。
 */
@WebMvcTest(RagController.class)
class RagControllerContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RagService ragService;

    private RagService.RagResult okResult() {
        RagResult r = new RagResult("7日内可无理由退货。",
                List.of("【1】tc_policy_v3.md#3.2"), List.of("切片内容"));
        r.setConfidenceScore(0.67);
        r.setNeedsHandoff(false);
        r.setHandoffReason("NONE");
        return r;
    }

    /* ---------- POST /api/rag/search 在线检索 ---------- */

    @Test
    void search_合法请求_返回Result包装与转人工三字段() throws Exception {
        when(ragService.search("7天内能退货吗", 5, "tenant-x")).thenReturn(Result.ok(okResult()));

        mockMvc.perform(post("/api/rag/search")
                        .header("X-Tenant-Id", "tenant-x")
                        .contentType("application/json")
                        .content("{\"query\":\"7天内能退货吗\",\"topK\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)))
                .andExpect(jsonPath("$.message", is("ok")))
                .andExpect(jsonPath("$.data.answer", containsString("无理由退货")))
                .andExpect(jsonPath("$.data.citations[0]", is("【1】tc_policy_v3.md#3.2")))
                .andExpect(jsonPath("$.data.confidenceScore", is(0.67)))
                .andExpect(jsonPath("$.data.needsHandoff", is(false)))
                .andExpect(jsonPath("$.data.handoffReason", is("NONE")));
    }

    @Test
    void search_缺X_Tenant_Id_回退default租户() throws Exception {
        when(ragService.search(anyString(), anyInt(), eq("default"))).thenReturn(Result.ok(okResult()));

        mockMvc.perform(post("/api/rag/search")
                        .contentType("application/json")
                        .content("{\"query\":\"运费谁出\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)));
    }

    @Test
    void search_缺query_返回400校验错误() throws Exception {
        mockMvc.perform(post("/api/rag/search")
                        .contentType("application/json")
                        .content("{\"topK\":5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is(400)))
                .andExpect(jsonPath("$.message", containsString("query")));
    }

    @Test
    void search_topK越界_返回400校验错误() throws Exception {
        mockMvc.perform(post("/api/rag/search")
                        .contentType("application/json")
                        .content("{\"query\":\"退货\",\"topK\":99}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is(400)));
    }

    @Test
    void search_topK缺省_默认5() throws Exception {
        when(ragService.search("退货", 5, "default")).thenReturn(Result.ok(okResult()));

        mockMvc.perform(post("/api/rag/search")
                        .contentType("application/json")
                        .content("{\"query\":\"退货\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)));
    }

    @Test
    void search_服务抛业务异常_统一Result错误包装() throws Exception {
        when(ragService.search(anyString(), anyInt(), anyString()))
                .thenThrow(new BizException(5001, "检索管道异常"));

        mockMvc.perform(post("/api/rag/search")
                        .contentType("application/json")
                        .content("{\"query\":\"退货\"}"))
                .andExpect(status().isOk()) // BizException 无 @ResponseStatus，统一返回 200 + code 区分
                .andExpect(jsonPath("$.code", is(5001)))
                .andExpect(jsonPath("$.message", is("检索管道异常")));
    }

    /* ---------- POST /api/rag/index 文档入库 ---------- */

    @Test
    void index_上传markdown文档_返回切片数() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "tc_policy_v3.md",
                "text/markdown", "# 政策\n\n## 3.2 无理由退货\n7日内可退。".getBytes());
        when(ragService.indexDocument(any(), eq("tenant-x"))).thenReturn(Result.ok(2L));

        mockMvc.perform(multipart("/api/rag/index").file(file)
                        .header("X-Tenant-Id", "tenant-x"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)))
                .andExpect(jsonPath("$.data", is(2)));
    }

    /* ---------- DELETE /api/rag/collections 清空知识库 ---------- */

    @Test
    void delete_按租户清空_返回成功() throws Exception {
        when(ragService.delete("tenant-x")).thenReturn(Result.ok());

        mockMvc.perform(delete("/api/rag/collections")
                        .header("X-Tenant-Id", "tenant-x"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)));
    }
}