package com.agent.data.toolinvocation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * L7 数据层：工具调用幂等记录（t_tool_invocation）
 * 对应 docs/design/architecture/20260901-tool-engine.md §2.2
 */
@Repository
public class ToolInvocationRepository {

    private final JdbcTemplate jdbc;

    public ToolInvocationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record InvocationRow(long invocationId, String idempotencyKey, String tenantId,
                                String toolName, String argsHash, String resultPayload,
                                String status, Instant createdAt, Instant finishedAt) {
    }

    private static final RowMapper<InvocationRow> ROW = (rs, i) -> new InvocationRow(
            rs.getLong("invocation_id"),
            rs.getString("idempotency_key"),
            rs.getString("tenant_id"),
            rs.getString("tool_name"),
            rs.getString("args_hash"),
            rs.getString("result_payload"),
            rs.getString("status"),
            toInstant(rs, "created_at"),
            toInstant(rs, "finished_at"));

    private static Instant toInstant(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        OffsetDateTime v = rs.getObject(col, OffsetDateTime.class);
        return v == null ? null : v.toInstant();
    }

    public Optional<InvocationRow> findByKey(String key) {
        return jdbc.query("SELECT * FROM t_tool_invocation WHERE idempotency_key = ?", ROW, key).stream().findFirst();
    }

    /** 幂等占用：插入 IN_PROGRESS，冲突（已存在）返回 false */
    @Transactional
    public boolean tryCreateInProgress(String key, String tenantId, String toolName, String argsHash) {
        try {
            int n = jdbc.update("""
                    INSERT INTO t_tool_invocation (idempotency_key, tenant_id, tool_name, args_hash, status)
                    VALUES (?, ?, ?, ?, 'IN_PROGRESS')
                    """, key, tenantId, toolName, argsHash);
            return n > 0;
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return false;
        }
    }

    public void markFinished(String key, String status, String resultPayload) {
        jdbc.update("UPDATE t_tool_invocation SET status = ?, result_payload = ?, finished_at = now() WHERE idempotency_key = ?",
                status, resultPayload, key);
    }

    /** 清理：SUCCESS 保留 90 天 / 其余 30 天 */
    public int cleanupExpired() {
        return jdbc.update("DELETE FROM t_tool_invocation WHERE "
                + "(status = 'SUCCESS' AND created_at < now() - interval '90 days') "
                + "OR (status <> 'SUCCESS' AND created_at < now() - interval '30 days')");
    }
}