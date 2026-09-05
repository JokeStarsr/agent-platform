package com.agent.data.toolmarket;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * L7 数据层：工具目录表（t_tool_catalog）
 * 对应 docs/design/architecture/20260905-tool-marketplace.md §3 / 表设计 20260905-t-tool-catalog.md
 */
@Repository
public class ToolCatalogRepository {

    private final JdbcTemplate jdbc;

    public ToolCatalogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record CatalogRow(long id, String toolName, int version, String displayName, String description,
                             String category, String parameters, String permission, String source,
                             String externalUrl, String externalToolName, String status, boolean enabled,
                             String ownerId, String testcaseJson, Instant createdAt, Instant updatedAt) {
    }

    private static final RowMapper<CatalogRow> ROW = (rs, i) -> new CatalogRow(
            rs.getLong("id"),
            rs.getString("tool_name"),
            rs.getInt("version"),
            rs.getString("display_name"),
            rs.getString("description"),
            rs.getString("category"),
            rs.getString("parameters"),
            rs.getString("permission"),
            rs.getString("source"),
            rs.getString("external_url"),
            rs.getString("external_tool_name"),
            rs.getString("status"),
            rs.getBoolean("enabled"),
            rs.getString("owner_id"),
            rs.getString("testcase_json"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at")));

    /** 目录列表（分页 + 过滤） */
    public List<CatalogRow> list(String category, String status, String keyword, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT * FROM t_tool_catalog WHERE 1=1");
        List<Object> params = new java.util.ArrayList<>();
        if (category != null && !category.isBlank()) {
            sql.append(" AND category = ?");
            params.add(category);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            params.add(status);
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (tool_name ILIKE ? OR display_name ILIKE ? OR description ILIKE ?)");
            params.add("%" + keyword + "%");
            params.add("%" + keyword + "%");
            params.add("%" + keyword + "%");
        }
        sql.append(" ORDER BY tool_name, version DESC LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);
        return jdbc.query(sql.toString(), ROW, params.toArray());
    }

    /** 某租户/某工具当前版本（version 最大的一条） */
    public Optional<CatalogRow> findByCurrent(String toolName) {
        List<CatalogRow> rows = jdbc.query(
                "SELECT * FROM t_tool_catalog WHERE tool_name = ? ORDER BY version DESC LIMIT 1",
                ROW, toolName);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** 按主键查一行 */
    public Optional<CatalogRow> findById(long id) {
        List<CatalogRow> rows = jdbc.query("SELECT * FROM t_tool_catalog WHERE id = ?", ROW, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** 某工具全部历史版本 */
    public List<CatalogRow> listVersions(String toolName) {
        return jdbc.query(
                "SELECT * FROM t_tool_catalog WHERE tool_name = ? ORDER BY version DESC",
                ROW, toolName);
    }

    /** 按 status 过滤的已发布列表（执行解析热路径） */
    public List<CatalogRow> listPublished() {
        return jdbc.query(
                "SELECT * FROM t_tool_catalog WHERE status = 'PUBLISHED' AND enabled = true ORDER BY tool_name",
                ROW);
    }

    /** 落一行新版本（version 自增） */
    public CatalogRow insert(CatalogRow r) {
        int maxVersion = findMaxVersion(r.toolName());
        int newVersion = maxVersion + 1;
        String sql = """
                INSERT INTO t_tool_catalog
                  (tool_name, version, display_name, description, category, parameters,
                   permission, source, external_url, external_tool_name, status, enabled,
                   owner_id, testcase_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        jdbc.update(sql, r.toolName(), newVersion, r.displayName(), r.description(), r.category(),
                r.parameters(), r.permission(), r.source(), r.externalUrl(), r.externalToolName(),
                r.status(), r.enabled(), r.ownerId(), r.testcaseJson());
        return findByToolVersion(r.toolName(), newVersion).orElseThrow();
    }

    /** 修改状态（DRAFT/PUBLISHED/OFF_SHELF），刷新 updated_at */
    public void updateStatus(long id, String status) {
        jdbc.update("UPDATE t_tool_catalog SET status = ?, updated_at = now() WHERE id = ?", status, id);
    }

    /** 修改 enabled 掩码（软停用） */
    public void updateEnabled(long id, boolean enabled) {
        jdbc.update("UPDATE t_tool_catalog SET enabled = ?, updated_at = now() WHERE id = ?", enabled, id);
    }

    /** 修改测试用例与描述（发布前可改） */
    public void updateContent(long id, String description, String testcaseJson) {
        jdbc.update("UPDATE t_tool_catalog SET description = ?, testcase_json = ?, updated_at = now() WHERE id = ?",
                description, testcaseJson, id);
    }

    /** 物理删除（仅误建清理；被授过权的工具禁止删除——已发布/被引用由上层控制） */
    public void delete(long id) {
        jdbc.update("DELETE FROM t_tool_catalog WHERE id = ?", id);
    }

    public boolean existsByName(String toolName) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM t_tool_catalog WHERE tool_name = ?",
                Integer.class, toolName);
        return n != null && n > 0;
    }

    private int findMaxVersion(String toolName) {
        Integer v = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version), 0) FROM t_tool_catalog WHERE tool_name = ?",
                Integer.class, toolName);
        return v == null ? 0 : v;
    }

    private Optional<CatalogRow> findByToolVersion(String toolName, int version) {
        List<CatalogRow> rows = jdbc.query(
                "SELECT * FROM t_tool_catalog WHERE tool_name = ? AND version = ?",
                ROW, toolName, version);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public long count(String category, String status, String keyword) {
        StringBuilder sql = new StringBuilder("SELECT count(*) FROM t_tool_catalog WHERE 1=1");
        List<Object> params = new java.util.ArrayList<>();
        if (category != null && !category.isBlank()) {
            sql.append(" AND category = ?");
            params.add(category);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            params.add(status);
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (tool_name ILIKE ? OR display_name ILIKE ? OR description ILIKE ?)");
            params.add("%" + keyword + "%");
            params.add("%" + keyword + "%");
            params.add("%" + keyword + "%");
        }
        return jdbc.queryForObject(sql.toString(), Long.class, params.toArray());
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}