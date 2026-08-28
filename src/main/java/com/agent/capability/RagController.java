package com.agent.capability;

import com.agent.capability.RagService.RagResult;
import com.agent.common.Result;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * L4 AI 能力层：RAG 检索 REST 接口
 * <p>
 * 对应设计文档 docs/design/api/20260829-rag-controller.md
 * 租户隔离：所有接口读取 X-Tenant-Id 请求头（缺省 "default"），透传 RagService 按租户过滤
 */
@RestController
@RequestMapping("/api/rag")
public class RagController {

    private final RagService ragService;

    public RagController(RagService ragService) {
        this.ragService = ragService;
    }

    /** 在线检索：查询改写 → 混合检索 → 重排 → Top-K → 生成 + 引用溯源 */
    @PostMapping("/search")
    public Result<RagResult> search(@RequestBody SearchRequest req,
                                    @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        return ragService.search(req.query(), req.topK(), tenantId);
    }

    /** 文档入库：Tika 解析 → 语义切块 → 向量化 → 写入租户 Collection */
    @PostMapping("/index")
    public Result<Long> index(@RequestParam("file") MultipartFile file,
                              @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        return ragService.indexDocument(file, tenantId);
    }

    /** 按租户清空知识库（评测重置用） */
    @DeleteMapping("/collections")
    public Result<Void> delete(@RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        return ragService.delete(tenantId);
    }

    /** 检索请求体 */
    public record SearchRequest(@NotBlank(message = "query 不能为空") String query,
                                @Min(value = 1, message = "topK 最小为 1")
                                @Max(value = 20, message = "topK 最大为 20")
                                Integer topK) {
        public SearchRequest {
            if (topK == null) {
                topK = 5;
            }
        }
    }
}
