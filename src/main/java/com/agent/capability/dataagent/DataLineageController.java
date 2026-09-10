package com.agent.capability.dataagent;

import com.agent.common.Result;
import com.agent.data.dataagent.DataLineageRepository.DataLineage;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * L4 能力层：数据血缘 API（W16 Step 3）
 * <p>提供血缘查询接口：按 trace_id 查询、租户历史、反向溯源（按表名）。</p>
 */
@RestController
@RequestMapping("/api/data-lineage")
public class DataLineageController {

    private final DataLineageService lineageService;

    public DataLineageController(DataLineageService lineageService) {
        this.lineageService = lineageService;
    }

    /**
     * 按 trace_id 查询血缘记录。
     */
    @GetMapping("/trace/{traceId}")
    public Result<DataLineage> getByTraceId(@PathVariable String traceId) {
        DataLineage lineage = lineageService.getByTraceId(traceId);
        if (lineage == null) {
            return Result.error(404, "血缘记录不存在");
        }
        return Result.ok(lineage);
    }

    /**
     * 查询租户的血缘历史（分页）。
     */
    @GetMapping("/history")
    public Result<Map<String, Object>> getHistory(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {

        List<DataLineage> records = lineageService.getHistory(tenantId, page, pageSize);
        long total = lineageService.getQueryCount(tenantId);

        Map<String, Object> response = Map.of(
                "page", page,
                "pageSize", pageSize,
                "total", total,
                "records", records
        );

        return Result.ok(response);
    }

    /**
     * 反向溯源：查询涉及指定表的分析记录。
     */
    @GetMapping("/table/{tableName}")
    public Result<List<DataLineage>> getByTableName(
            @PathVariable String tableName,
            @RequestParam(defaultValue = "10") int limit) {

        List<DataLineage> records = lineageService.getByTableName(tableName, limit);
        return Result.ok(records);
    }

    /**
     * 查询租户的查询次数统计。
     */
    @GetMapping("/stats")
    public Result<Map<String, Object>> getStats(@RequestHeader("X-Tenant-Id") String tenantId) {
        long queryCount = lineageService.getQueryCount(tenantId);
        return Result.ok(Map.of("queryCount", queryCount));
    }

    /**
     * 清理旧记录（管理接口，保留最近 N 条）。
     */
    @DeleteMapping("/cleanup")
    public Result<Map<String, Object>> cleanup(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestParam(defaultValue = "1000") int keepRecent) {

        int deleted = lineageService.cleanupOldRecords(tenantId, keepRecent);
        return Result.ok(Map.of("deleted", deleted, "keepRecent", keepRecent));
    }
}
