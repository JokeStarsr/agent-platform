package com.agent.data.memory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * L7 数据层：长期记忆持久化（t_user_memory）
 * 对应 docs/design/architecture/20260901-memory-context.md §4.1。tenant_id+user_id 双隔离（红线）。
 */
@Repository
public class UserMemoryRepository {

    private final JdbcTemplate jdbc;

    public UserMemoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record UserMemoryRow(long id, String tenantId, String userId, String field, String value,
                                double confidence, String status, String source,
                                Instant createdAt, Instant updatedAt) {
    }

    private static final RowMapper<UserMemoryRow> ROW = (rs, i) -> new UserMemoryRow(
            rs.getLong("id"),
            rs.getString("tenant_id"),
            rs.getString("user_id"),
            rs.getString("field"),
            rs.getString("value"),
            rs.getDouble("confidence"),
            rs.getString("status"),
            rs.getString("source"),
            toInstant(rs, "created_at"),
            toInstant(rs, "updated_at"));

    private static Instant toInstant(ResultSet rs, String col) throws SQLException {
        OffsetDateTime v = rs.getObject(col, OffsetDateTime.class);
        return v == null ? null : v.toInstant();
    }

    public long insert(String tenantId, String userId, String field, String value,
                       double confidence, String status, String source, String embedding) {
        jdbc.update("""
                  INSERT INTO t_user_memory (tenant_id, user_id, field, value, confidence, status, source, embedding)
                  VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                  """, tenantId, userId, field, value, confidence, status, source, embedding);
        return jdbc.queryForObject("SELECT currval(pg_get_serial_sequence('t_user_memory', 'id'))", Long.class);
    }

    public Optional<UserMemoryRow> findById(long id) {
        return jdbc.query("SELECT * FROM t_user_memory WHERE id = ?", ROW, id).stream().findFirst();
    }

    /** 活跃画像：有向量则近邻召回，否则按近期降序（embedding 缺失降级路径） */
    public List<UserMemoryRow> selectActive(String tenantId, String userId, Integer topK,
                                            String queryEmbedding) {
        if (queryEmbedding != null && !queryEmbedding.isBlank()) {
            String sql = """
                    SELECT * FROM t_user_memory
                     WHERE tenant_id = ? AND user_id = ? AND status = 'ACTIVE'
                     ORDER BY embedding <=> ?::vector
                     LIMIT ?
                    """;
            return jdbc.query(sql, ROW, tenantId, userId, queryEmbedding, effectiveTopK(topK));
        }
        return jdbc.query("""
                  SELECT * FROM t_user_memory
                   WHERE tenant_id = ? AND user_id = ? AND status = 'ACTIVE'
                   ORDER BY created_at DESC
                   LIMIT ?
                  """, ROW, tenantId, userId, effectiveTopK(topK));
    }

    public List<UserMemoryRow> selectPending(String tenantId, String userId) {
        return jdbc.query("""
                  SELECT * FROM t_user_memory
                   WHERE tenant_id = ? AND user_id = ? AND status = 'PENDING_CONFIRM'
                   ORDER BY created_at
                  """, ROW, tenantId, userId);
    }

    public void setStatus(long id, String status) {
        jdbc.update("UPDATE t_user_memory SET status = ?, updated_at = now() WHERE id = ?", status, id);
    }

    private static int effectiveTopK(Integer topK) {
        return topK == null || topK <= 0 ? 5 : topK;
    }

    /**
     * 清理超期的记忆记录（冲刺3）
     */
    public int deleteOldRecords(int retentionDays) {
        String sql = "DELETE FROM t_user_memory WHERE created_at < now() - interval '" + retentionDays + " days'";
        return jdbc.update(sql);
    }
}
