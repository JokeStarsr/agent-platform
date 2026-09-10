package com.agent.data.openplatform;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * L7 数据层：API Key 仓库（W17）
 * <p>存储 API Key 哈希（非明文），支持按 hash 查询、按租户列表、创建/禁用/删除。</p>
 */
@Repository
public class ApiKeyRepository {

    private final JdbcTemplate jdbc;

    public ApiKeyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record ApiKey(
            long id,
            String tenantId,
            String apiKeyPrefix,
            String apiKeyHash,
            String name,
            String status,
            Instant expiresAt,
            Instant createdAt,
            Instant updatedAt
    ) {}

    private static final RowMapper<ApiKey> ROW = (rs, i) -> new ApiKey(
            rs.getLong("id"),
            rs.getString("tenant_id"),
            rs.getString("api_key_prefix"),
            rs.getString("api_key_hash"),
            rs.getString("name"),
            rs.getString("status"),
            rs.getTimestamp("expires_at") != null ? rs.getTimestamp("expires_at").toInstant() : null,
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
    );

    /**
     * 创建 API Key（返回 id）。
     */
    public long insert(String tenantId, String apiKeyPrefix, String apiKeyHash, String name, Instant expiresAt) {
        String sql = """
                INSERT INTO t_api_key (tenant_id, api_key_prefix, api_key_hash, name, expires_at)
                VALUES (?, ?, ?, ?, ?)
                RETURNING id
                """;
        Long id = jdbc.queryForObject(sql, Long.class, tenantId, apiKeyPrefix, apiKeyHash, name,
                expiresAt != null ? java.sql.Timestamp.from(expiresAt) : null);
        return id != null ? id : 0;
    }

    /**
     * 按哈希查询 API Key（验证时使用）。
     */
    public Optional<ApiKey> findByHash(String apiKeyHash) {
        String sql = "SELECT * FROM t_api_key WHERE api_key_hash = ?";
        List<ApiKey> keys = jdbc.query(sql, ROW, apiKeyHash);
        return keys.isEmpty() ? Optional.empty() : Optional.of(keys.get(0));
    }

    /**
     * 按租户查询 API Key 列表。
     */
    public List<ApiKey> findByTenantId(String tenantId) {
        String sql = "SELECT * FROM t_api_key WHERE tenant_id = ? ORDER BY created_at DESC";
        return jdbc.query(sql, ROW, tenantId);
    }

    /**
     * 按 ID 查询 API Key。
     */
    public Optional<ApiKey> findById(long id) {
        String sql = "SELECT * FROM t_api_key WHERE id = ?";
        List<ApiKey> keys = jdbc.query(sql, ROW, id);
        return keys.isEmpty() ? Optional.empty() : Optional.of(keys.get(0));
    }

    /**
     * 更新 API Key 状态（禁用/启用）。
     */
    public int updateStatus(long id, String status) {
        String sql = "UPDATE t_api_key SET status = ?, updated_at = NOW() WHERE id = ?";
        return jdbc.update(sql, status, id);
    }

    /**
     * 删除 API Key。
     */
    public int deleteById(long id) {
        String sql = "DELETE FROM t_api_key WHERE id = ?";
        return jdbc.update(sql, id);
    }

    /**
     * 按租户删除所有 API Key。
     */
    public int deleteByTenantId(String tenantId) {
        String sql = "DELETE FROM t_api_key WHERE tenant_id = ?";
        return jdbc.update(sql, tenantId);
    }
}
