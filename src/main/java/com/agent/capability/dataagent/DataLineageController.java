package com.agent.capability.dataagent;

import com.agent.common.Result;
import com.agent.data.dataagent.DataLineageRepository.DataLineage;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * L4 能力层：数据血缘 API（W16 Step 3 + Step 4）
 * <p>提供血缘查询接口：按 trace_id 查询、租户历史、反向溯源（按表名），以及报告导出。</p>
 */
@RestController
@RequestMapping("/api/data-lineage")
public class DataLineageController {

    private final DataLineageService lineageService;
    private final ReportExporter reportExporter;

    public DataLineageController(DataLineageService lineageService, ReportExporter reportExporter) {
        this.lineageService = lineageService;
        this.reportExporter = reportExporter;
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

    /**
     * 导出分析报告（Markdown 格式）。
     */
    @GetMapping("/export/markdown/{traceId}")
    public ResponseEntity<String> exportMarkdown(@PathVariable String traceId) {
        DataLineage lineage = lineageService.getByTraceId(traceId);
        if (lineage == null) {
            return ResponseEntity.notFound().build();
        }

        // 从血缘记录中重建分析结果（简化版：仅导出 SQL 和表格）
        String markdown = reportExporter.exportMarkdown(
                lineage.question(),
                buildQueryResultFromLineage(lineage),
                null,  // chartResult 需要额外存储
                null,  // verification 已在 lineage 中
                lineage
        );

        return ResponseEntity.ok()
                .header("Content-Type", "text/markdown; charset=utf-8")
                .header("Content-Disposition", "attachment; filename=\"report-" + traceId + ".md\"")
                .body(markdown);
    }

    /**
     * 导出分析报告（HTML 格式）。
     */
    @GetMapping("/export/html/{traceId}")
    public ResponseEntity<String> exportHtml(@PathVariable String traceId) {
        DataLineage lineage = lineageService.getByTraceId(traceId);
        if (lineage == null) {
            return ResponseEntity.notFound().build();
        }

        String html = reportExporter.exportHtml(
                lineage.question(),
                buildQueryResultFromLineage(lineage),
                null,  // chartResult 需要额外存储
                null,  // verification 已在 lineage 中
                lineage
        );

        return ResponseEntity.ok()
                .header("Content-Type", "text/html; charset=utf-8")
                .header("Content-Disposition", "attachment; filename=\"report-" + traceId + ".html\"")
                .body(html);
    }

    // ========== 辅助方法 ==========

    private DataAgentService.QueryResult buildQueryResultFromLineage(DataLineage lineage) {
        // 从血缘记录中重建 QueryResult（简化版）
        // 注意：完整的 rows 数据需要额外存储，这里仅重建元数据
        return new DataAgentService.QueryResult(
                lineage.question(),
                lineage.generatedSql(),
                lineage.columnsUsed().stream()
                        .map(col -> col.getOrDefault("column", ""))
                        .toList(),
                List.of(),  // rows 未存储
                lineage.resultRows(),
                false,
                lineage.durationMs(),
                0,  // llmDurationMs 未单独存储
                0,  // sqlDurationMs 未单独存储
                null  // verification 已在 lineage 中
        );
    }
}
