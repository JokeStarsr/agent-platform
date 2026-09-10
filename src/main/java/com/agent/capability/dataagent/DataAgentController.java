package com.agent.capability.dataagent;

import com.agent.capability.dataagent.DataAgentService.QueryResult;
import com.agent.common.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

/**
 * L4 能力层：Data Agent NL2SQL REST 接口
 * <p>
 * 对应设计文档 docs/design/architecture/20260910-data-agent.md
 * 租户隔离：所有接口读取 X-Tenant-Id 请求头（缺省 "default"）
 */
@RestController
@RequestMapping("/api/data-agent")
public class DataAgentController {

    private final DataAgentService dataAgentService;
    private final ResultCache resultCache;

    public DataAgentController(DataAgentService dataAgentService, ResultCache resultCache) {
        this.dataAgentService = dataAgentService;
        this.resultCache = resultCache;
    }

    /**
     * NL2SQL 查询（不含数值复算）：自然语言 → SQL → 执行 → 返回结果 + SQL 展示
     */
    @PostMapping("/query")
    public Result<QueryResult> query(@Valid @RequestBody QueryRequest req,
                                     @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        return Result.ok(dataAgentService.query(tenantId, req.question(), req.maxRows()));
    }

    /**
     * NL2SQL 查询（含数值复算校验）：自然语言 → SQL → 执行 → 复算校验 → 返回结果
     * <p>P4 闸门要求：数值结论 100% 经复算校验（代码复算，非 LLM 互判）</p>
     */
    @PostMapping("/query/verify")
    public Result<QueryResult> queryWithVerification(@Valid @RequestBody QueryRequest req,
                                                      @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        return Result.ok(dataAgentService.queryWithVerification(
                tenantId, req.question(), req.maxRows(), req.requireVerification()));
    }

    /**
     * 刷新 Schema 缓存（管理 API，触发重新加载 t_schema_metadata + 清空结果缓存）
     */
    @PostMapping("/schema/refresh")
    public Result<Void> refreshSchema() {
        dataAgentService.refreshSchemaCache();
        resultCache.clear();
        return Result.ok(null);
    }

    /**
     * 结果缓存统计（命中率/容量）。
     */
    @GetMapping("/cache/stats")
    public Result<java.util.Map<String, Object>> cacheStats() {
        return Result.ok(resultCache.stats());
    }

    /**
     * 查询请求体
     */
    public record QueryRequest(
            @NotBlank(message = "question 不能为空") String question,
            @Min(value = 1, message = "maxRows 最小为 1")
            @Max(value = 500, message = "maxRows 最大为 500")
            Integer maxRows,
            Boolean requireVerification) {
        public QueryRequest {
            if (maxRows == null) {
                maxRows = 100;
            }
            if (requireVerification == null) {
                requireVerification = true;  // P4 闸门默认开启复算校验
            }
        }
    }
}
