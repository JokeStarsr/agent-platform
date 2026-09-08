package com.agent.data.skillhub;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import jakarta.annotation.PostConstruct;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * L7 数据层：技能目录仓库（docs/design/table/20260905-t-skill.md，W14）。
 * 表：t_skill（目录，当前版本）+ t_skill_version（版本历史）。
 */
@Repository
public class SkillRepository {

    private final JdbcTemplate jdbc;

    public SkillRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 启动时幂等建表 */
    @PostConstruct
    public void initSchema() {
        init();
    }

    /** 初始化建表（幂等） */
    public void init() {
        createSchema();
    }

    /* ---------- t_skill（目录） ---------- */

    public record SkillRow(
            long id,
            String name,
            String displayName,
            String description,
            String category,
            String currentVersion,
            String status,
            boolean enabled,
            String ownerId,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {}

    private static final RowMapper<SkillRow> ROW = (rs, rowNum) -> new SkillRow(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("display_name"),
            rs.getString("description"),
            rs.getString("category"),
            rs.getString("current_version"),
            rs.getString("status"),
            rs.getBoolean("enabled"),
            rs.getString("owner_id"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class)
    );

    /** 建表（幂等） */
    public void createSchema() {
        // t_skill
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS t_skill (
                id BIGSERIAL PRIMARY KEY,
                name VARCHAR(64) NOT NULL UNIQUE,
                display_name VARCHAR(128) NOT NULL,
                description VARCHAR(512) NOT NULL,
                category VARCHAR(32) NOT NULL DEFAULT 'utility',
                current_version VARCHAR(20) NOT NULL DEFAULT '1.0.0',
                status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
                enabled BOOLEAN NOT NULL DEFAULT TRUE,
                owner_id VARCHAR(64) NOT NULL DEFAULT 'platform',
                created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
            )
            """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_skill_status ON t_skill (status, enabled)");

        // t_skill_version
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS t_skill_version (
                id BIGSERIAL PRIMARY KEY,
                skill_id BIGINT NOT NULL,
                version VARCHAR(20) NOT NULL,
                manifest_json JSONB NOT NULL,
                status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
                test_result JSONB,
                released_at TIMESTAMPTZ,
                created_at TIMESTAMPTZ NOT NULL DEFAULT now()
            )
            """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_skill_version_skill ON t_skill_version (skill_id)");
    }

    /** 插入新技能（DRAFT） */
    public SkillRow insert(SkillRow row) {
        String sql = """
            INSERT INTO t_skill (name, display_name, description, category, current_version, status, enabled, owner_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            RETURNING id, name, display_name, description, category, current_version, status, enabled, owner_id, created_at, updated_at
            """;
        return jdbc.queryForObject(sql, ROW, row.name(), row.displayName(), row.description(),
                row.category(), row.currentVersion(), row.status(), row.enabled(), row.ownerId());
    }

    /** 更新技能状态 */
    public void updateStatus(long id, String status) {
        jdbc.update("UPDATE t_skill SET status=?, updated_at=now() WHERE id=?", status, id);
    }

    /** 更新技能当前版本 + 状态 */
    public void updateVersionAndStatus(long id, String version, String status) {
        jdbc.update("UPDATE t_skill SET current_version=?, status=?, updated_at=now() WHERE id=?", version, status, id);
    }

    /** 启用/禁用 */
    public void setEnabled(long id, boolean enabled) {
        jdbc.update("UPDATE t_skill SET enabled=?, updated_at=now() WHERE id=?", enabled, id);
    }

    /** 按名称查当前版本 */
    public Optional<SkillRow> findByName(String name) {
        return jdbc.query("SELECT * FROM t_skill WHERE name=?", ROW, name).stream().findFirst();
    }

    /** 按 ID 查 */
    public Optional<SkillRow> findById(long id) {
        return jdbc.query("SELECT * FROM t_skill WHERE id=?", ROW, id).stream().findFirst();
    }

    /** 列表（分页+过滤） */
    public List<SkillRow> list(int limit, int offset, String status, String category) {
        StringBuilder sql = new StringBuilder("SELECT * FROM t_skill WHERE 1=1");
        if (status != null) sql.append(" AND status=?");
        if (category != null) sql.append(" AND category=?");
        sql.append(" ORDER BY id DESC LIMIT ? OFFSET ?");

        if (status != null && category != null) {
            return jdbc.query(sql.toString(), ROW, status, category, limit, offset);
        } else if (status != null) {
            return jdbc.query(sql.toString(), ROW, status, limit, offset);
        } else if (category != null) {
            return jdbc.query(sql.toString(), ROW, category, limit, offset);
        } else {
            return jdbc.query(sql.toString(), ROW, limit, offset);
        }
    }

    /** 总数 */
    public long count(String status, String category) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM t_skill WHERE 1=1");
        if (status != null) sql.append(" AND status=?");
        if (category != null) sql.append(" AND category=?");
        if (status != null && category != null) {
            return jdbc.queryForObject(sql.toString(), Long.class, status, category);
        } else if (status != null) {
            return jdbc.queryForObject(sql.toString(), Long.class, status);
        } else if (category != null) {
            return jdbc.queryForObject(sql.toString(), Long.class, category);
        } else {
            return jdbc.queryForObject(sql.toString(), Long.class);
        }
    }

    /* ---------- t_skill_version（版本历史） ---------- */

    public record VersionRow(
            long id,
            long skillId,
            String version,
            String manifestJson,
            String status,
            String testResult,
            OffsetDateTime releasedAt,
            OffsetDateTime createdAt
    ) {}

    private static final RowMapper<VersionRow> VROW = (rs, rowNum) -> new VersionRow(
            rs.getLong("id"),
            rs.getLong("skill_id"),
            rs.getString("version"),
            rs.getString("manifest_json"),
            rs.getString("status"),
            rs.getString("test_result"),
            rs.getObject("released_at", OffsetDateTime.class),
            rs.getObject("created_at", OffsetDateTime.class)
    );

    /** 插入版本记录 */
    public VersionRow insertVersion(VersionRow row) {
        String sql = """
            INSERT INTO t_skill_version (skill_id, version, manifest_json, status, test_result, released_at)
            VALUES (?, ?, ?::jsonb, ?, ?::jsonb, ?)
            RETURNING id, skill_id, version, manifest_json, status, test_result, released_at, created_at
            """;
        return jdbc.queryForObject(sql, VROW, row.skillId(), row.version(), row.manifestJson(),
                row.status(), row.testResult(), row.releasedAt());
    }

    /** 更新版本状态 + 测试结果 + 发布时间 */
    public void updateVersionStatus(long versionId, String status, String testResult, OffsetDateTime releasedAt) {
        String sql = "UPDATE t_skill_version SET status=?, test_result=?::jsonb, released_at=? WHERE id=?";
        jdbc.update(sql, status, testResult, releasedAt, versionId);
    }

    /** 查某技能的所有版本 */
    public List<VersionRow> listVersions(long skillId) {
        return jdbc.query("SELECT * FROM t_skill_version WHERE skill_id=? ORDER BY id DESC", VROW, skillId);
    }

    /** 查某版本详情 */
    public Optional<VersionRow> findVersion(long skillId, String version) {
        return jdbc.query("SELECT * FROM t_skill_version WHERE skill_id=? AND version=?", VROW, skillId, version).stream().findFirst();
    }

    /** 查当前发布版本 */
    public Optional<VersionRow> findPublishedVersion(long skillId) {
        return jdbc.query("SELECT * FROM t_skill_version WHERE skill_id=? AND status='PUBLISHED' ORDER BY id DESC LIMIT 1", VROW, skillId).stream().findFirst();
    }

    /** 是否存在已发布技能（种子幂等检查用） */
    public boolean hasPublished() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM t_skill WHERE status='PUBLISHED'", Long.class);
        return n != null && n > 0;
    }

    /** 清空技能表（仅供种子重建） */
    public void deleteAll() {
        jdbc.update("DELETE FROM t_skill_version");
        jdbc.update("DELETE FROM t_skill");
    }

    /** 删除单个技能及其版本（仅种子残留清理用） */
    public void deleteSkill(long skillId) {
        jdbc.update("DELETE FROM t_skill_version WHERE skill_id=?", skillId);
        jdbc.update("DELETE FROM t_skill WHERE id=?", skillId);
    }
}