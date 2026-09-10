package com.agent.data.tokenmeter;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * L7 数据层：Token 用量明细仓库（W17）
 * <p>记录每次 LLM 调用的 Token 消耗（prompt/completion/total）、费用、耗时。
 * 表按月分区，支持大数据量。</p>
 */
@Repository
public class TokenUsageRepository {

    private final JdbcTemplate jdbc;

    public TokenUsageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record TokenUsage(
            long id,
            String traceId,
            String tenantId,
            String appId,
            String modelName,
            int promptTokens,
            int completionTokens,
            int totalTokens,
            BigDecimal cost,
            long durationMs,
            Instant createdAt
    ) {}

    private static final RowMapper<TokenUsage> ROW = (rs, i) -> new TokenUsage(
            rs.getLong("id"),
            rs.getString("trace_id"),
            rs.getString("tenant_id"),
            rs.getString("app_id"),
            rs.getString("model_name"),
            rs.getInt("prompt_tokens"),
            rs.getInt("completion_tokens"),
            rs.getInt("total_tokens"),
            rs.getBigDecimal("cost"),
            rs.getLong("duration_ms"),
            rs.getTimestamp("created_at").toInstant()
    );

    /**
     * 插入 Token 用量记录。
     */
    public long insert(String traceId, String tenantId, String appId, String modelName,
                       int promptTokens, int completionTokens, int totalTokens,
                       BigDecimal cost, long durationMs) {
        String sql = """
                INSERT INTO t_token_usage (trace_id, tenant_id, app_id, model_name,
                                           prompt_tokens, completion_tokens, total_tokens,
                                           cost, duration_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """;
        Long id = jdbc.queryForObject(sql, Long.class,
                traceId, tenantId, appId, modelName,
                promptTokens, completionTokens, totalTokens,
                cost, durationMs);
        return id != null ? id : 0;
    }

    /**
     * 按 trace_id 查询用量记录。
     */
    public List<TokenUsage> findByTraceId(String traceId) {
        String sql = "SELECT * FROM t_token_usage WHERE trace_id = ? ORDER BY created_at DESC";
        return jdbc.query(sql, ROW, traceId);
    }

    /**
     * 按租户查询用量记录（分页）。
     */
    public List<TokenUsage> findByTenantId(String tenantId, int limit, int offset) {
        String sql = "SELECT * FROM t_token_usage WHERE tenant_id = ? ORDER BY created_at DESC LIMIT ? OFFSET ?";
        return jdbc.query(sql, ROW, tenantId, limit, offset);
    }

    /**
     * 查询今日 Token 消耗最高的请求（Top N）。
     */
    public List<TokenUsage> findTopTokenConsumers(LocalDate date, int limit) {
        String sql = """
                SELECT * FROM t_token_usage
                WHERE created_at >= ? AND created_at < ?
                ORDER BY total_tokens DESC
                LIMIT ?
                """;
        java.sql.Timestamp start = java.sql.Timestamp.valueOf(date.atStartOfDay());
        java.sql.Timestamp end = java.sql.Timestamp.valueOf(date.plusDays(1).atStartOfDay());
        return jdbc.query(sql, ROW, start, end, limit);
    }

    /**
     * 查询指定日期范围的用量记录。
     */
    public List<TokenUsage> findByDateRange(LocalDate startDate, LocalDate endDate) {
        String sql = """
                SELECT * FROM t_token_usage
                WHERE created_at >= ? AND created_at < ?
                ORDER BY created_at DESC
                """;
        java.sql.Timestamp start = java.sql.Timestamp.valueOf(startDate.atStartOfDay());
        java.sql.Timestamp end = java.sql.Timestamp.valueOf(endDate.plusDays(1).atStartOfDay());
        return jdbc.query(sql, ROW, start, end);
    }
}
