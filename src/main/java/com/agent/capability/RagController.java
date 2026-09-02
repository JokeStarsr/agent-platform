package com.agent.capability;

import com.agent.capability.RagService.RagResult;
import com.agent.common.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.util.Map;

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

    /** 在线检索：查询改写 → 混合检索 → 重排 → Top-K → 生成 + 引用溯源（P1 二期：增加可选 appId） */
    @PostMapping("/search")
    public Result<RagResult> search(@Valid @RequestBody SearchRequest req,
                                    @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        return ragService.search(req.query(), req.topK(), tenantId, req.appId());
    }

    /** SSE 流式检索（P1 收口 #2）：检索 → 逐 token 生成 → done 元数据。
     *  每行一个紧凑 JSON（type=retrieval/answer/done/error），前端按 type 分发。
     *  对应设计文档 docs/design/api/20260830-rag-stream.md
     *  P1 二期：增加可选 appId 参数，空串回退 yml 兜底。
     */
    @PostMapping(value = "/search/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> searchStream(@Valid @RequestBody SearchRequest req,
                                     @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        return ragService.streamSearch(req.query(), req.topK(), tenantId, req.appId());
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

    /** 知识库集合状态（按租户统计，只读） */
    @GetMapping("/collections")
    public Result<Map<String, Object>> collections(@RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        return ragService.collections(tenantId);
    }

    /** 检索请求体（P1 二期：增加可选 appId；空串回退 yml 兜底配置） */
    public record SearchRequest(@NotBlank(message = "query 不能为空") String query,
                                @Min(value = 1, message = "topK 最小为 1")
                                @Max(value = 20, message = "topK 最大为 20")
                                Integer topK,
                                String appId) {
        public SearchRequest {
            if (topK == null) {
                topK = 5;
            }
        }
    }
}
