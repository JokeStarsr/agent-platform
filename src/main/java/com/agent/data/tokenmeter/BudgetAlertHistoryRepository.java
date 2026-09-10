package com.agent.data.tokenmeter;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * L7 数据层：预算告警历史仓库（W17）
 * <p>记录告警历史，支持去重查询（同一租户同一档位每日仅告警一次）。</p>
 */
@Repository
public class BudgetAlertHistoryRepository {

    private final JdbcTemplate jdbc;

    public BudgetAlertHistoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record BudgetAlertHistory(
            long id,
            String tenantId,
            String alertType,
            BigDecimal usageRate,
            BigDecimal todayUsage,
            BigDecimal dailyBudget,
            Instant notifiedAt,
            Instant createdAt
    ) {}

    private static final RowMapper<BudgetAlertHistory> ROW = (rs, i) -> new BudgetAlertHistory(
            rs.getLong("id"),
            rs.getString("tenant_id"),
            rs.getString("alert_type"),
            rs.getBigDecimal("usage_rate"),
            rs.getBigDecimal("today_usage"),
            rs.getBigDecimal("daily_budget"),
            rs.getTimestamp("notified_at").toInstant(),
            rs.getTimestamp("created_at").toInstant()
    );

    /**
     * 插入告警历史。
     */
    public long insert(String tenantId, String alertType, BigDecimal usageRate,
                       BigDecimal todayUsage, BigDecimal dailyBudget) {
        String sql = """
                INSERT INTO t_budget_alert_history (tenant_id, alert_type, usage_rate, today_usage, daily_budget)
                VALUES (?, ?, ?, ?, ?)
                RETURNING id
                """;
        Long id = jdbc.queryForObject(sql, Long.class, tenantId, alertType, usageRate, todayUsage, dailyBudget);
        return id != null ? id : 0;
    }

    /**
     * 检查今日是否已发送同类型告警（去重）。
     */
    public boolean hasAlertToday(String tenantId, String alertType, LocalDate today) {
        String sql = """
                SELECT COUNT(*) FROM t_budget_alert_history
                WHERE tenant_id = ? AND alert_type = ? AND DATE(notified_at) = ?
                """;
        Integer count = jdbc.queryForObject(sql, Integer.class, tenantId, alertType, java.sql.Date.valueOf(today));
        return count != null && count > 0;
    }

    /**
     * 查询租户告警历史（分页）。
     */
    public List<BudgetAlertHistory> findByTenantId(String tenantId, int limit, int offset) {
        String sql = """
                SELECT * FROM t_budget_alert_history
                WHERE tenant_id = ?
                ORDER BY notified_at DESC
                LIMIT ? OFFSET ?
                """;
        return jdbc.query(sql, ROW, tenantId, limit, offset);
    }

    /**
     * 查询租户今日告警（用于前端展示）。
     */
    public List<BudgetAlertHistory> findTodayAlerts(String tenantId, LocalDate today) {
        String sql = """
                SELECT * FROM t_budget_alert_history
                WHERE tenant_id = ? AND DATE(notified_at) = ?
                ORDER BY notified_at DESC
                """;
        return jdbc.query(sql, ROW, tenantId, java.sql.Date.valueOf(today));
    }
}
