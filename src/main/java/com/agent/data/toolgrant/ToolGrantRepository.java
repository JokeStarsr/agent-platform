package com.agent.data.toolgrant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * L7 数据层：租户级工具授权表（t_tool_grant）
 * 对应 docs/design/architecture/20260904-mcp-gateway.md §5
 */
@Repository
public class ToolGrantRepository {

    private final JdbcTemplate jdbc;

    public ToolGrantRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record GrantRow(long id, String tenantId, String toolName, String permission, boolean enabled) {
    }

    private static final org.springframework.jdbc.core.RowMapper<GrantRow> ROW = (rs, i) -> new GrantRow(
            rs.getLong("id"), rs.getString("tenant_id"), rs.getString("tool_name"),
            rs.getString("permission"), rs.getBoolean("enabled"));

    /** 授权判定（热路径）：单行精确查，enabled=true 才放行 */
    public boolean isAllowed(String tenantId, String toolName) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM t_tool_grant WHERE tenant_id = ? AND tool_name = ? AND enabled = true",
                Integer.class, tenantId, toolName);
        return n != null && n > 0;
    }

    /** 某租户授权列表（管理 API） */
    public List<GrantRow> listByTenant(String tenantId) {
        return jdbc.query(
                "SELECT * FROM t_tool_grant WHERE tenant_id = ? ORDER BY tool_name",
                ROW, tenantId);
    }

    /** 授权 / 撤销（启用 / 停用） */
    public void setEnabled(String tenantId, String toolName, boolean enabled) {
        jdbc.update("""
                INSERT INTO t_tool_grant (tenant_id, tool_name, permission, enabled, granted_by)
                VALUES (?, ?, 'READ', ?, 'admin_api')
                ON CONFLICT (tenant_id, tool_name)
                DO UPDATE SET enabled = EXCLUDED.enabled, updated_at = now()
                """, tenantId, toolName, enabled);
    }
}
