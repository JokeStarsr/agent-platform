package com.agent.data.tokenmeter;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * L7 数据层：Token 用量日聚合仓库（W17）
 * <p>按日/租户/应用/模型聚合 Token 消耗和费用，加速看板查询。</p>
 */
@Repository
public class TokenUsageDailyRepository {

    private final JdbcTemplate jdbc;

    public TokenUsageDailyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record TokenUsageDaily(
            long id,
            LocalDate statDate,
            String tenantId,
            String appId,
            String modelName,
            long totalCalls,
            long totalTokens,
            BigDecimal totalCost,
            Instant createdAt,
            Instant updatedAt
    ) {}

    private static final RowMapper<TokenUsageDaily> ROW = (rs, i) -> new TokenUsageDaily(
            rs.getLong("id"),
            rs.getDate("stat_date").toLocalDate(),
            rs.getString("tenant_id"),
            rs.getString("app_id"),
            rs.getString("model_name"),
            rs.getLong("total_calls"),
            rs.getLong("total_tokens"),
            rs.getBigDecimal("total_cost"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
    );

    /**
     * UPSERT 日聚合记录（累加 Token 和费用）。
     */
    public void upsert(LocalDate statDate, String tenantId, String appId, String modelName,
                       long totalTokens, BigDecimal totalCost) {
        String sql = """
                INSERT INTO t_token_usage_daily (stat_date, tenant_id, app_id, model_name,
                                                 total_calls, total_tokens, total_cost)
                VALUES (?, ?, ?, ?, 1, ?, ?)
                ON CONFLICT (stat_date, tenant_id, app_id, model_name) DO UPDATE SET
                    total_calls = t_token_usage_daily.total_calls + 1,
                    total_tokens = t_token_usage_daily.total_tokens + EXCLUDED.total_tokens,
                    total_cost = t_token_usage_daily.total_cost + EXCLUDED.total_cost,
                    updated_at = NOW()
                """;
        jdbc.update(sql, java.sql.Date.valueOf(statDate), tenantId, appId, modelName, totalTokens, totalCost);
    }

    /**
     * 查询租户今日 Token 用量（用于限流检查）。
     */
    public long getTodayTokenUsage(String tenantId, LocalDate today) {
        String sql = """
                SELECT COALESCE(SUM(total_tokens), 0)
                FROM t_token_usage_daily
                WHERE tenant_id = ? AND stat_date = ?
                """;
        Long total = jdbc.queryForObject(sql, Long.class, tenantId, java.sql.Date.valueOf(today));
        return total != null ? total : 0;
    }

    /**
     * 查询租户今日费用（用于预算告警）。
     */
    public BigDecimal getTodayCost(String tenantId, LocalDate today) {
        String sql = """
                SELECT COALESCE(SUM(total_cost), 0)
                FROM t_token_usage_daily
                WHERE tenant_id = ? AND stat_date = ?
                """;
        BigDecimal total = jdbc.queryForObject(sql, BigDecimal.class, tenantId, java.sql.Date.valueOf(today));
        return total != null ? total : BigDecimal.ZERO;
    }

    /**
     * 查询租户指定日期范围的日聚合数据（用于趋势图表）。
     */
    public List<TokenUsageDaily> findByDateRange(String tenantId, LocalDate startDate, LocalDate endDate) {
        String sql = """
                SELECT * FROM t_token_usage_daily
                WHERE tenant_id = ? AND stat_date >= ? AND stat_date <= ?
                ORDER BY stat_date
                """;
        return jdbc.query(sql, ROW, tenantId,
                java.sql.Date.valueOf(startDate),
                java.sql.Date.valueOf(endDate));
    }

    /**
     * 按应用统计指定日期范围的用量（用于饼图）。
     */
    public List<TokenUsageDaily> groupByApp(String tenantId, LocalDate startDate, LocalDate endDate) {
        String sql = """
                SELECT
                    stat_date,
                    tenant_id,
                    app_id,
                    'all' AS model_name,
                    SUM(total_calls) AS total_calls,
                    SUM(total_tokens) AS total_tokens,
                    SUM(total_cost) AS total_cost,
                    MIN(created_at) AS created_at,
                    MAX(updated_at) AS updated_at
                FROM t_token_usage_daily
                WHERE tenant_id = ? AND stat_date >= ? AND stat_date <= ?
                GROUP BY stat_date, tenant_id, app_id
                ORDER BY total_tokens DESC
                """;
        return jdbc.query(sql, ROW, tenantId,
                java.sql.Date.valueOf(startDate),
                java.sql.Date.valueOf(endDate));
    }

    /**
     * 按模型统计指定日期范围的用量（用于饼图）。
     */
    public List<TokenUsageDaily> groupByModel(String tenantId, LocalDate startDate, LocalDate endDate) {
        String sql = """
                SELECT
                    stat_date,
                    tenant_id,
                    'all' AS app_id,
                    model_name,
                    SUM(total_calls) AS total_calls,
                    SUM(total_tokens) AS total_tokens,
                    SUM(total_cost) AS total_cost,
                    MIN(created_at) AS created_at,
                    MAX(updated_at) AS updated_at
                FROM t_token_usage_daily
                WHERE tenant_id = ? AND stat_date >= ? AND stat_date <= ?
                GROUP BY stat_date, tenant_id, model_name
                ORDER BY total_tokens DESC
                """;
        return jdbc.query(sql, ROW, tenantId,
                java.sql.Date.valueOf(startDate),
                java.sql.Date.valueOf(endDate));
    }

    /**
     * 查询租户指定日期的总用量（用于统计卡片）。
     */
    public Optional<TokenUsageDaily> getDailySummary(String tenantId, LocalDate date) {
        String sql = """
                SELECT
                    stat_date,
                    tenant_id,
                    'all' AS app_id,
                    'all' AS model_name,
                    SUM(total_calls) AS total_calls,
                    SUM(total_tokens) AS total_tokens,
                    SUM(total_cost) AS total_cost,
                    MIN(created_at) AS created_at,
                    MAX(updated_at) AS updated_at
                FROM t_token_usage_daily
                WHERE tenant_id = ? AND stat_date = ?
                GROUP BY stat_date, tenant_id
                """;
        List<TokenUsageDaily> results = jdbc.query(sql, ROW, tenantId, java.sql.Date.valueOf(date));
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }
}
