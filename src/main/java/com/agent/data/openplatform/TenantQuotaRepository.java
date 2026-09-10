package com.agent.data.openplatform;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * L7 数据层：租户配额仓库（W17）
 * <p>管理租户级限流配额（QPS、日 Token 配额、日预算）和预算超支熔断标记。</p>
 */
@Repository
public class TenantQuotaRepository {

    private final JdbcTemplate jdbc;

    public TenantQuotaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record TenantQuota(
            long id,
            String tenantId,
            int qps,
            long dailyTokenQuota,
            BigDecimal dailyBudget,
            boolean budgetExceeded,
            Instant createdAt,
            Instant updatedAt
    ) {}

    private static final RowMapper<TenantQuota> ROW = (rs, i) -> new TenantQuota(
            rs.getLong("id"),
            rs.getString("tenant_id"),
            rs.getInt("qps"),
            rs.getLong("daily_token_quota"),
            rs.getBigDecimal("daily_budget"),
            rs.getBoolean("budget_exceeded"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
    );

    /**
     * 创建或更新租户配额（UPSERT）。
     */
    public void upsert(String tenantId, int qps, long dailyTokenQuota, BigDecimal dailyBudget) {
        String sql = """
                INSERT INTO t_tenant_quota (tenant_id, qps, daily_token_quota, daily_budget)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (tenant_id) DO UPDATE SET
                    qps = EXCLUDED.qps,
                    daily_token_quota = EXCLUDED.daily_token_quota,
                    daily_budget = EXCLUDED.daily_budget,
                    updated_at = NOW()
                """;
        jdbc.update(sql, tenantId, qps, dailyTokenQuota, dailyBudget);
    }

    /**
     * 按租户查询配额。
     */
    public Optional<TenantQuota> findByTenantId(String tenantId) {
        String sql = "SELECT * FROM t_tenant_quota WHERE tenant_id = ?";
        List<TenantQuota> quotas = jdbc.query(sql, ROW, tenantId);
        return quotas.isEmpty() ? Optional.empty() : Optional.of(quotas.get(0));
    }

    /**
     * 查询所有租户配额。
     */
    public List<TenantQuota> findAll() {
        String sql = "SELECT * FROM t_tenant_quota ORDER BY tenant_id";
        return jdbc.query(sql, ROW);
    }

    /**
     * 标记租户预算超支（熔断）。
     */
    public int markBudgetExceeded(String tenantId) {
        String sql = "UPDATE t_tenant_quota SET budget_exceeded = TRUE, updated_at = NOW() WHERE tenant_id = ?";
        return jdbc.update(sql, tenantId);
    }

    /**
     * 清除租户预算超支标记（恢复）。
     */
    public int clearBudgetExceeded(String tenantId) {
        String sql = "UPDATE t_tenant_quota SET budget_exceeded = FALSE, updated_at = NOW() WHERE tenant_id = ?";
        return jdbc.update(sql, tenantId);
    }

    /**
     * 删除租户配额。
     */
    public int deleteByTenantId(String tenantId) {
        String sql = "DELETE FROM t_tenant_quota WHERE tenant_id = ?";
        return jdbc.update(sql, tenantId);
    }
}
