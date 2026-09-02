package com.agent.data.application;

/**
 * 注意：包名刻意用 application 而非 app——ArchUnit 分层规则 layer("App").definedBy("..app..")
 * 会匹配任何含 "app" 段的包（如 data.app），造成 data 层被误判为 App 层。
 */
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * L7 数据层：应用注册表持久化（t_app）
 * 对应 docs/design/table/20260902-app-table.md；config_json 以 JSON 文本落库。
 */
@Repository
public class AppRepository {

    private final JdbcTemplate jdbc;

    public AppRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record AppRow(long id, String tenantId, String appId, String name, String status,
                         String configJson, int version, Instant createdAt, Instant updatedAt) {
    }

    private static final RowMapper<AppRow> APP_ROW = (rs, i) -> new AppRow(
            rs.getLong("id"),
            rs.getString("tenant_id"),
            rs.getString("app_id"),
            rs.getString("name"),
            rs.getString("status"),
            rs.getString("config_json"),
            rs.getInt("version"),
            toInstant(rs, "created_at"),
            toInstant(rs, "updated_at"));

    private static final int MAX_PAGE_SIZE = 100;

    private static Instant toInstant(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        OffsetDateTime v = rs.getObject(col, OffsetDateTime.class);
        return v == null ? null : v.toInstant();
    }

    public List<AppRow> listByTenant(String tenantId) {
        return jdbc.query("""
                SELECT id, tenant_id, app_id, name, status, config_json::text, version, created_at, updated_at
                FROM t_app WHERE tenant_id = ? ORDER BY app_id
                """, APP_ROW, tenantId);
    }

    public Optional<AppRow> findByAppId(String tenantId, String appId) {
        return jdbc.query("""
                SELECT id, tenant_id, app_id, name, status, config_json::text, version, created_at, updated_at
                FROM t_app WHERE tenant_id = ? AND app_id = ?
                """, APP_ROW, tenantId, appId).stream().findFirst();
    }

    /** 全量（含 GLOBAL 平台应用，AppRegistry 启动装载用） */
    public List<AppRow> listAll() {
        return jdbc.query("""
                SELECT id, tenant_id, app_id, name, status, config_json::text, version, created_at, updated_at
                FROM t_app ORDER BY tenant_id, app_id
                """, APP_ROW);
    }

    /** 租户可见数（租户内 + GLOBAL 平台应用） */
    public long countVisible(String tenantId) {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM t_app WHERE tenant_id = ? OR tenant_id = 'GLOBAL'", Long.class, tenantId);
        return n == null ? 0 : n;
    }

    /** 租户可见分页（租户内 + GLOBAL 平台应用） */
    public List<AppRow> pageVisible(String tenantId, int page, int size) {
        int p = Math.max(page, 1);
        int sz = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return jdbc.query("""
                SELECT id, tenant_id, app_id, name, status, config_json::text, version, created_at, updated_at
                FROM t_app WHERE tenant_id = ? OR tenant_id = 'GLOBAL'
                ORDER BY (tenant_id = 'GLOBAL') DESC, app_id LIMIT ? OFFSET ?
                """, APP_ROW, tenantId, sz, (p - 1) * sz);
    }

    public AppRow create(String tenantId, String appId, String name, String status, String configJson) {
        jdbc.update("""
                INSERT INTO t_app (tenant_id, app_id, name, status, config_json)
                VALUES (?, ?, ?, ?, ?::jsonb)
                """, tenantId, appId, name, status, configJson);
        jdbc.queryForObject("SELECT currval(pg_get_serial_sequence('t_app', 'id'))", Long.class);
        return findByAppId(tenantId, appId).orElseThrow();
    }

    /** 配置变更：version+1 + 刷新时间戳 */
    public void updateConfig(String tenantId, String appId, String configJson) {
        jdbc.update("""
                UPDATE t_app SET config_json = ?::jsonb, version = version + 1, updated_at = now()
                WHERE tenant_id = ? AND app_id = ?
                """, configJson, tenantId, appId);
    }

    public void updateStatus(String tenantId, String appId, String status) {
        jdbc.update("UPDATE t_app SET status = ?, updated_at = now() WHERE tenant_id = ? AND app_id = ?",
                status, tenantId, appId);
    }

    /** 种子幂等：存在则跳过（AppSeedRegistrar 用） */
    public boolean upsertSeed(String tenantId, String appId, String name, String configJson) {
        String status = jdbc.query("SELECT status FROM t_app WHERE tenant_id = ? AND app_id = ?",
                (rs, i) -> rs.getString("status"), tenantId, appId).stream().findFirst().orElse(null);
        if (status != null) {
            return false;
        }
        create(tenantId, appId, name, "ENABLED", configJson);
        return true;
    }
}