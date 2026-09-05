package com.agent.data.multiagent;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * L7 数据层：多智能体根运行表（t_multi_agent_run）
 * 对应 docs/design/architecture/20260905-multi-agent.md §12.4
 */
@Repository
public class MultiAgentRunRepository {

    private final JdbcTemplate jdbc;

    public MultiAgentRunRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record RunRow(long id, String tenantId, String topology, String task, String appId,
                         String stagesJson, String planJson, String status, String finalAnswer,
                         int totalToken, int budgetLimit, Instant createdAt, Instant updatedAt) {
    }

    private static final RowMapper<RunRow> ROW = (rs, i) -> new RunRow(
            rs.getLong("id"),
            rs.getString("tenant_id"),
            rs.getString("topology"),
            rs.getString("task"),
            rs.getString("app_id"),
            rs.getString("stages_json"),
            rs.getString("plan_json"),
            rs.getString("status"),
            rs.getString("final_answer"),
            rs.getInt("total_token"),
            rs.getInt("budget_limit"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at")));

    public RunRow create(RunRow r) {
        String sql = """
                INSERT INTO t_multi_agent_run
                  (tenant_id, topology, task, app_id, stages_json, plan_json, status, budget_limit)
                VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
                RETURNING id
                """;
        Long id = jdbc.queryForObject(sql, Long.class, r.tenantId(), r.topology(), r.task(), r.appId(),
                r.stagesJson(), r.planJson(), r.status(), r.budgetLimit());
        return findById(id).orElseThrow();
    }

    public Optional<RunRow> findById(long id) {
        List<RunRow> rows = jdbc.query("SELECT * FROM t_multi_agent_run WHERE id = ?", ROW, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<RunRow> list(String tenantId, String status, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT * FROM t_multi_agent_run WHERE tenant_id = ?");
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(tenantId);
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        sql.append(" ORDER BY id DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbc.query(sql.toString(), ROW, args.toArray());
    }

    public long count(String tenantId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM t_multi_agent_run WHERE tenant_id = ?", Integer.class, tenantId);
        return n == null ? 0 : n;
    }

    public void updateStatus(long id, String status) {
        jdbc.update("UPDATE t_multi_agent_run SET status = ?, updated_at = now() WHERE id = ?", status, id);
    }

    public void updatePlan(long id, String planJson) {
        jdbc.update("UPDATE t_multi_agent_run SET plan_json = ?::jsonb, status = 'RUNNING', updated_at = now() WHERE id = ?",
                planJson, id);
    }

    public void updateFinal(long id, String finalAnswer, int totalToken) {
        jdbc.update("UPDATE t_multi_agent_run SET final_answer = ?, total_token = ?, status = 'COMPLETED', updated_at = now() WHERE id = ?",
                finalAnswer, totalToken, id);
    }

    /** 子 run 父指针回填（多智能体子任务） */
    public void markParent(long runId, long parentRunId) {
        jdbc.update("UPDATE t_agent_run SET parent_run_id = ? WHERE id = ?", parentRunId, runId);
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}