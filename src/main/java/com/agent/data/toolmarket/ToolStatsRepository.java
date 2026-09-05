package com.agent.data.toolmarket;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * L7 数据层：工具调用统计（聚合 t_tool_invocation，docs/design/architecture/20260905-tool-marketplace.md §6）。
 * 统计窗口内每工具的调用量 / 成功 / 失败 / 平均时延 / 重放占比。
 */
@Repository
public class ToolStatsRepository {

    private final JdbcTemplate jdbc;

    public ToolStatsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record ToolStat(String toolName, long total, long success, long failed,
                           double avgLatencyMs, long replayCount) {
    }

    private static final RowMapper<ToolStat> ROW = (rs, i) -> new ToolStat(
            rs.getString("tool_name"),
            rs.getLong("total"),
            rs.getLong("success"),
            rs.getLong("failed"),
            rs.getDouble("avg_latency_ms"),
            rs.getLong("replay_count"));

    /** 某租户在窗口内的按工具统计（默认按调用量倒序） */
    public List<ToolStat> byTenant(String tenantId, int days) {
        String sql = """
                SELECT tool_name,
                       count(*)                                                    AS total,
                       count(*) FILTER (WHERE status = 'SUCCESS')                  AS success,
                       count(*) FILTER (WHERE status = 'FAILED')                   AS failed,
                       COALESCE(avg(EXTRACT(EPOCH FROM (finished_at - created_at)) * 1000), 0) AS avg_latency_ms,
                       count(*) FILTER (WHERE status = 'SUCCESS' AND result_payload IS NOT NULL) AS replay_count
                FROM t_tool_invocation
                WHERE tenant_id = ? AND created_at >= now() - (?::int * interval '1 day')
                GROUP BY tool_name
                ORDER BY total DESC
                """;
        return jdbc.query(sql, ROW, tenantId, days);
    }
}