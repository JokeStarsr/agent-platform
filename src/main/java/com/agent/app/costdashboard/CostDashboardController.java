package com.agent.app.costdashboard;

import com.agent.common.Result;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * L7 数据层：成本看板 API（W17）
 * <p>提供用量统计、趋势图表、Top 消耗请求、预算告警历史等接口。</p>
 */
@RestController
@RequestMapping("/api/cost-dashboard")
public class CostDashboardController {

    private final CostDashboardService costDashboardService;

    public CostDashboardController(CostDashboardService costDashboardService) {
        this.costDashboardService = costDashboardService;
    }

    /**
     * 查询用量统计（今日/昨日/环比）。
     */
    @GetMapping("/usage/tenant")
    public Result<Map<String, Object>> getUsageStats(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestParam(defaultValue = "today") String period) {
        Map<String, Object> stats = costDashboardService.getUsageStats(tenantId, period);
        return Result.ok(stats);
    }

    /**
     * 查询趋势数据（近 N 天 Token/费用折线图）。
     */
    @GetMapping("/trend")
    public Result<Map<String, Object>> getTrend(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestParam(defaultValue = "7") int days) {
        Map<String, Object> trend = costDashboardService.getTrend(tenantId, days);
        return Result.ok(trend);
    }

    /**
     * 查询按应用分组的用量（饼图）。
     */
    @GetMapping("/usage/app")
    public Result<Map<String, Object>> getUsageByApp(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestParam(defaultValue = "today") String period) {
        Map<String, Object> usage = costDashboardService.getUsageByApp(tenantId, period);
        return Result.ok(usage);
    }

    /**
     * 查询按模型分组的用量（饼图）。
     */
    @GetMapping("/usage/model")
    public Result<Map<String, Object>> getUsageByModel(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestParam(defaultValue = "today") String period) {
        Map<String, Object> usage = costDashboardService.getUsageByModel(tenantId, period);
        return Result.ok(usage);
    }

    /**
     * 查询 Top 消耗请求。
     */
    @GetMapping("/top-requests")
    public Result<Map<String, Object>> getTopRequests(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestParam(defaultValue = "10") int limit) {
        Map<String, Object> requests = costDashboardService.getTopRequests(tenantId, limit);
        return Result.ok(requests);
    }

    /**
     * 查询预算告警历史。
     */
    @GetMapping("/budget/alerts")
    public Result<Map<String, Object>> getBudgetAlerts(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestParam(defaultValue = "20") int limit) {
        Map<String, Object> alerts = costDashboardService.getBudgetAlerts(tenantId, limit);
        return Result.ok(alerts);
    }
}
