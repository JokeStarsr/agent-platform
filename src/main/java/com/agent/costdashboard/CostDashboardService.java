package com.agent.costdashboard;

import com.agent.data.tokenmeter.BudgetAlertHistoryRepository;
import com.agent.data.tokenmeter.BudgetAlertHistoryRepository.BudgetAlertHistory;
import com.agent.data.tokenmeter.TokenUsageDailyRepository;
import com.agent.data.tokenmeter.TokenUsageDailyRepository.TokenUsageDaily;
import com.agent.data.tokenmeter.TokenUsageRepository;
import com.agent.data.tokenmeter.TokenUsageRepository.TokenUsage;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * L7 数据层：成本看板服务（W17）
 * <p>提供用量统计、趋势图表、Top 消耗请求、预算告警历史等看板数据。</p>
 */
@Service
public class CostDashboardService {

    private final TokenUsageRepository tokenUsageRepo;
    private final TokenUsageDailyRepository tokenUsageDailyRepo;
    private final BudgetAlertHistoryRepository alertHistoryRepo;

    public CostDashboardService(TokenUsageRepository tokenUsageRepo,
                                TokenUsageDailyRepository tokenUsageDailyRepo,
                                BudgetAlertHistoryRepository alertHistoryRepo) {
        this.tokenUsageRepo = tokenUsageRepo;
        this.tokenUsageDailyRepo = tokenUsageDailyRepo;
        this.alertHistoryRepo = alertHistoryRepo;
    }

    /**
     * 查询用量统计（今日/昨日/环比）。
     */
    public Map<String, Object> getUsageStats(String tenantId, String period) {
        LocalDate today = LocalDate.now();
        LocalDate targetDate = switch (period) {
            case "yesterday" -> today.minusDays(1);
            case "week" -> today.minusWeeks(1);
            case "month" -> today.minusMonths(1);
            default -> today;  // "today"
        };

        Optional<TokenUsageDaily> targetSummary = tokenUsageDailyRepo.getDailySummary(tenantId, targetDate);
        Optional<TokenUsageDaily> yesterdaySummary = tokenUsageDailyRepo.getDailySummary(tenantId, today.minusDays(1));

        long targetTokens = targetSummary.map(TokenUsageDaily::totalTokens).orElse(0L);
        BigDecimal targetCost = targetSummary.map(TokenUsageDaily::totalCost).orElse(BigDecimal.ZERO);
        long targetCalls = targetSummary.map(TokenUsageDaily::totalCalls).orElse(0L);

        long yesterdayTokens = yesterdaySummary.map(TokenUsageDaily::totalTokens).orElse(0L);
        BigDecimal yesterdayCost = yesterdaySummary.map(TokenUsageDaily::totalCost).orElse(BigDecimal.ZERO);

        // 计算环比增长率
        double tokenGrowthRate = calculateGrowthRate(targetTokens, yesterdayTokens);
        double costGrowthRate = calculateGrowthRate(targetCost.doubleValue(), yesterdayCost.doubleValue());

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("period", period);
        stats.put("date", targetDate.toString());
        stats.put("totalTokens", targetTokens);
        stats.put("totalCost", targetCost);
        stats.put("totalCalls", targetCalls);
        stats.put("yesterdayTokens", yesterdayTokens);
        stats.put("yesterdayCost", yesterdayCost);
        stats.put("tokenGrowthRate", tokenGrowthRate);
        stats.put("costGrowthRate", costGrowthRate);

        return stats;
    }

    /**
     * 查询趋势数据（近 N 天 Token/费用折线图）。
     */
    public Map<String, Object> getTrend(String tenantId, int days) {
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(days - 1);

        List<TokenUsageDaily> dailyRecords = tokenUsageDailyRepo.findByDateRange(tenantId, startDate, endDate);

        // 按日期聚合
        Map<LocalDate, TokenUsageDaily> dailyMap = dailyRecords.stream()
                .collect(Collectors.toMap(TokenUsageDaily::statDate, r -> r, (a, b) -> {
                    // 合并同一天多条记录
                    return new TokenUsageDaily(
                            a.id(), a.statDate(), a.tenantId(), "all", "all",
                            a.totalCalls() + b.totalCalls(),
                            a.totalTokens() + b.totalTokens(),
                            a.totalCost().add(b.totalCost()),
                            a.createdAt(), a.updatedAt()
                    );
                }));

        // 生成趋势数据（填充缺失日期为 0）
        List<Map<String, Object>> tokenTrend = new ArrayList<>();
        List<Map<String, Object>> costTrend = new ArrayList<>();

        for (int i = 0; i < days; i++) {
            LocalDate date = startDate.plusDays(i);
            TokenUsageDaily record = dailyMap.get(date);

            long tokens = record != null ? record.totalTokens() : 0;
            BigDecimal cost = record != null ? record.totalCost() : BigDecimal.ZERO;

            tokenTrend.add(Map.of("date", date.toString(), "tokens", tokens));
            costTrend.add(Map.of("date", date.toString(), "cost", cost));
        }

        Map<String, Object> trend = new LinkedHashMap<>();
        trend.put("days", days);
        trend.put("tokenTrend", tokenTrend);
        trend.put("costTrend", costTrend);

        return trend;
    }

    /**
     * 查询按应用分组的用量（饼图）。
     */
    public Map<String, Object> getUsageByApp(String tenantId, String period) {
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = getStartDate(period, endDate);

        List<TokenUsageDaily> records = tokenUsageDailyRepo.groupByApp(tenantId, startDate, endDate);

        // 按应用聚合
        Map<String, AppUsage> appUsageMap = new LinkedHashMap<>();
        for (TokenUsageDaily r : records) {
            String appId = r.appId() != null ? r.appId() : "unknown";
            appUsageMap.computeIfAbsent(appId, k -> new AppUsage())
                    .add(r.totalTokens(), r.totalCost());
        }

        // 计算占比
        long totalTokens = appUsageMap.values().stream().mapToLong(u -> u.tokens).sum();
        List<Map<String, Object>> apps = appUsageMap.entrySet().stream()
                .map(e -> {
                    Map<String, Object> app = new LinkedHashMap<>();
                    app.put("appId", e.getKey());
                    app.put("tokens", e.getValue().tokens);
                    app.put("cost", e.getValue().cost);
                    app.put("percentage", totalTokens > 0
                            ? (double) e.getValue().tokens / totalTokens * 100 : 0);
                    return app;
                })
                .sorted((a, b) -> Long.compare((long) b.get("tokens"), (long) a.get("tokens")))
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("period", period);
        result.put("apps", apps);

        return result;
    }

    /**
     * 查询按模型分组的用量（饼图）。
     */
    public Map<String, Object> getUsageByModel(String tenantId, String period) {
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = getStartDate(period, endDate);

        List<TokenUsageDaily> records = tokenUsageDailyRepo.groupByModel(tenantId, startDate, endDate);

        // 按模型聚合
        Map<String, ModelUsage> modelUsageMap = new LinkedHashMap<>();
        for (TokenUsageDaily r : records) {
            String modelName = r.modelName() != null ? r.modelName() : "unknown";
            modelUsageMap.computeIfAbsent(modelName, k -> new ModelUsage())
                    .add(r.totalCalls(), r.totalCost());
        }

        // 计算占比
        BigDecimal totalCost = modelUsageMap.values().stream()
                .map(u -> u.cost).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<Map<String, Object>> models = modelUsageMap.entrySet().stream()
                .map(e -> {
                    Map<String, Object> model = new LinkedHashMap<>();
                    model.put("modelName", e.getKey());
                    model.put("calls", e.getValue().calls);
                    model.put("cost", e.getValue().cost);
                    model.put("percentage", totalCost.compareTo(BigDecimal.ZERO) > 0
                            ? e.getValue().cost.divide(totalCost, 4, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100)).doubleValue() : 0);
                    return model;
                })
                .sorted((a, b) -> Double.compare((double) b.get("cost"), (double) a.get("cost")))
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("period", period);
        result.put("models", models);

        return result;
    }

    /**
     * 查询 Top 消耗请求。
     */
    public Map<String, Object> getTopRequests(String tenantId, int limit) {
        List<TokenUsage> topUsages = tokenUsageRepo.findTopTokenConsumers(LocalDate.now(), limit);

        List<Map<String, Object>> requests = topUsages.stream()
                .map(u -> {
                    Map<String, Object> req = new LinkedHashMap<>();
                    req.put("traceId", u.traceId());
                    req.put("appId", u.appId());
                    req.put("modelName", u.modelName());
                    req.put("tokens", u.totalTokens());
                    req.put("cost", u.cost());
                    req.put("durationMs", u.durationMs());
                    req.put("createdAt", u.createdAt().toString());
                    return req;
                })
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requests", requests);

        return result;
    }

    /**
     * 查询预算告警历史。
     */
    public Map<String, Object> getBudgetAlerts(String tenantId, int limit) {
        List<BudgetAlertHistory> alerts = alertHistoryRepo.findByTenantId(tenantId, limit, 0);

        List<Map<String, Object>> alertList = alerts.stream()
                .map(a -> {
                    Map<String, Object> alert = new LinkedHashMap<>();
                    alert.put("alertType", a.alertType());
                    alert.put("usageRate", a.usageRate());
                    alert.put("todayUsage", a.todayUsage());
                    alert.put("dailyBudget", a.dailyBudget());
                    alert.put("notifiedAt", a.notifiedAt().toString());
                    return alert;
                })
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("alerts", alertList);

        return result;
    }

    // ========== 辅助方法 ==========

    private LocalDate getStartDate(String period, LocalDate endDate) {
        return switch (period) {
            case "today" -> endDate;
            case "week" -> endDate.minusWeeks(1);
            case "month" -> endDate.minusMonths(1);
            default -> endDate.minusDays(7);  // 默认 7 天
        };
    }

    private double calculateGrowthRate(long current, long previous) {
        if (previous == 0) return current > 0 ? 100.0 : 0.0;
        return (double) (current - previous) / previous * 100;
    }

    private double calculateGrowthRate(double current, double previous) {
        if (previous == 0) return current > 0 ? 100.0 : 0.0;
        return (current - previous) / previous * 100;
    }

    // ========== 内部类 ==========

    private static class AppUsage {
        long tokens = 0;
        BigDecimal cost = BigDecimal.ZERO;

        void add(long tokens, BigDecimal cost) {
            this.tokens += tokens;
            this.cost = this.cost.add(cost);
        }
    }

    private static class ModelUsage {
        long calls = 0;
        BigDecimal cost = BigDecimal.ZERO;

        void add(long calls, BigDecimal cost) {
            this.calls += calls;
            this.cost = this.cost.add(cost);
        }
    }
}
