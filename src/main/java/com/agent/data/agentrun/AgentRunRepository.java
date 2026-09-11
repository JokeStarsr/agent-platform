package com.agent.data.agentrun;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * L7 数据层：Agent Runtime 运行与步骤持久化（t_agent_run / t_agent_step）
 * 对应 docs/design/architecture/20260831-agent-runtime.md §4
 */
@Repository
public class AgentRunRepository {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;

    public AgentRunRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /* ---------- t_agent_run ---------- */

    private record RunRow(long runId, String tenantId, String appId, String task, String status,
                          int maxSteps, int tokenBudget, int timeoutMs, int loopThreshold,
                          int stepsDone, int tokensUsed, String traceId,
                          String pendingTool, String pendingArgs, String unfinishedReason,
                          Instant createdAt, Instant startedAt, Instant finishedAt) {
    }

    private static final RowMapper<RunRow> RUN_ROW = (rs, i) -> new RunRow(
            rs.getLong("run_id"),
            rs.getString("tenant_id"),
            rs.getString("app_id"),
            rs.getString("task"),
            rs.getString("status"),
            rs.getInt("max_steps"),
            rs.getInt("token_budget"),
            rs.getInt("timeout_ms"),
            rs.getInt("loop_threshold"),
            rs.getInt("steps_done"),
            rs.getInt("tokens_used"),
            rs.getString("trace_id"),
            rs.getString("pending_tool"),
            rs.getString("pending_args"),
            rs.getString("unfinished_reason"),
            toInstant(rs, "created_at"),
            toInstant(rs, "started_at"),
            toInstant(rs, "finished_at"));

    private static Instant toInstant(ResultSet rs, String col) throws SQLException {
        OffsetDateTime v = rs.getObject(col, OffsetDateTime.class);
        return v == null ? null : v.toInstant();
    }

    @Transactional
    public long createRun(String tenantId, String appId, String task, String status,
                          int maxSteps, int tokenBudget, int timeoutMs, int loopThreshold, String traceId) {
        jdbc.update("""
                    INSERT INTO t_agent_run (tenant_id, app_id, task, status, max_steps, token_budget, timeout_ms, loop_threshold, trace_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, tenantId, appId, task, status, maxSteps, tokenBudget, timeoutMs, loopThreshold, traceId);
        Long id = jdbc.queryForObject("SELECT currval(pg_get_serial_sequence('t_agent_run', 'run_id'))", Long.class);
        return id;
    }

    public Optional<RunRow> findRun(long runId) {
        List<RunRow> rows = jdbc.query("SELECT * FROM t_agent_run WHERE run_id = ?", RUN_ROW, runId);
        return rows.stream().findFirst();
    }

    /** 运行列表行（管理台展示，L3 编排层可见的只读视图） */
    public record RunListItem(long runId, String appId, String task, String status,
                              int maxSteps, int stepsDone, int tokensUsed,
                              Instant createdAt, Instant finishedAt) {
    }

    private static final RowMapper<RunListItem> RUN_LIST_ITEM = (rs, i) -> new RunListItem(
            rs.getLong("run_id"),
            rs.getString("app_id"),
            rs.getString("task"),
            rs.getString("status"),
            rs.getInt("max_steps"),
            rs.getInt("steps_done"),
            rs.getInt("tokens_used"),
            toInstant(rs, "created_at"),
            toInstant(rs, "finished_at"));

    private static final int MAX_PAGE_SIZE = 100;

    /** 运行总数（租户范围，可选状态筛选） */
    public long countRuns(String tenantId, String status) {
        StringBuilder sql = new StringBuilder("SELECT count(*) FROM t_agent_run WHERE tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        Long n = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return n == null ? 0 : n;
    }

    /** 分页运行列表（走 idx_agent_run_tenant_created，page 从 1 起） */
    public List<RunListItem> pageRuns(String tenantId, int page, int size, String status) {
        int p = Math.max(page, 1);
        int sz = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        StringBuilder sql = new StringBuilder("""
                SELECT run_id, app_id, task, status, max_steps, steps_done, tokens_used, created_at, finished_at
                FROM t_agent_run WHERE tenant_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        sql.append(" ORDER BY created_at DESC LIMIT ? OFFSET ?");
        args.add(sz);
        args.add((p - 1) * sz);
        return jdbc.query(sql.toString(), RUN_LIST_ITEM, args.toArray());
    }

    /** 运行状态汇总（详情页展示） */
    public Map<String, Object> runDetail(long runId) {
        RunRow r = findRun(runId).orElse(null);
        if (r == null) {
            return null;
        }
        // Map.of 键值对上限 10 对，此处字段多改用 LinkedHashMap 保持列顺序
        Map<String, Object> detail = new java.util.LinkedHashMap<>();
        detail.put("runId", r.runId());
        detail.put("tenantId", r.tenantId());
        detail.put("appId", r.appId());
        detail.put("task", r.task());
        detail.put("status", r.status());
        detail.put("maxSteps", r.maxSteps());
        detail.put("tokenBudget", r.tokenBudget());
        detail.put("timeoutMs", r.timeoutMs());
        detail.put("stepsDone", r.stepsDone());
        detail.put("tokensUsed", r.tokensUsed());
        detail.put("traceId", r.traceId());
        detail.put("pendingTool", r.pendingTool());
        detail.put("pendingArgs", r.pendingArgs());
        detail.put("unfinishedReason", r.unfinishedReason());
        detail.put("createdAt", r.createdAt() == null ? null : r.createdAt().toString());
        detail.put("startedAt", r.startedAt() == null ? null : r.startedAt().toString());
        detail.put("finishedAt", r.finishedAt() == null ? null : r.finishedAt().toString());
        return detail;
    }

    public void updateStatus(long runId, String status, String unfinishedReason, int stepsDone, int tokensUsed) {
        jdbc.update("UPDATE t_agent_run SET status = ?, unfinished_reason = ?, steps_done = ?, tokens_used = ?, finished_at = now() WHERE run_id = ?",
                status, unfinishedReason, stepsDone, tokensUsed, runId);
    }

    public void markStarted(long runId, int consumedTokens) {
        jdbc.update("UPDATE t_agent_run SET status = 'RUNNING', started_at = now(), tokens_used = ? WHERE run_id = ?", consumedTokens, runId);
    }

    public void updateProgress(long runId, int stepsDone, int tokensUsed) {
        jdbc.update("UPDATE t_agent_run SET steps_done = ?, tokens_used = ? WHERE run_id = ?", stepsDone, tokensUsed, runId);
    }

    public void hangForApproval(long runId, String tool, Map<String, Object> args) {
        String argsJson = writeJson(args);
        jdbc.update("UPDATE t_agent_run SET status = 'WAITING_APPROVAL', pending_tool = ?, pending_args = ? WHERE run_id = ?",
                tool, argsJson, runId);
    }

    public void resumeFromApproval(long runId, String status) {
        jdbc.update("UPDATE t_agent_run SET status = ?, pending_tool = NULL, pending_args = NULL WHERE run_id = ?", status, runId);
    }

    public int countRunning(String tenantId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM t_agent_run WHERE tenant_id = ? AND status IN ('RUNNING','WAITING_APPROVAL')",
                Integer.class, tenantId);
        return n == null ? 0 : n;
    }

    public List<Long> findStuckPendingAllTenants() {
        return jdbc.query("SELECT run_id FROM t_agent_run WHERE status = 'WAITING_APPROVAL'", (rs, i) -> rs.getLong("run_id"));
    }

    /** 审批用的待执行内容 */
    public Optional<PendingApproval> findPending(long runId) {
        RunRow r = findRun(runId).orElse(null);
        if (r == null || r.pendingTool() == null) {
            return Optional.empty();
        }
        Map<String, Object> args = readJson(r.pendingArgs());
        return Optional.of(new PendingApproval(r.tenantId(), r.pendingTool(), args));
    }

    public record PendingApproval(String tenantId, String tool, Map<String, Object> args) {
    }

    /* ---------- t_agent_step（trace 事件落库） ---------- */

    /** L7 层自持的步骤数据行（不依赖 L3 编排层类型，保持依赖只允许向下） */
    public record StepRow(String phase, int stepNo, String tool, String argsHash, String resultHash,
                          int llmTokens, long latencyMs, String decision) {
    }

    @Transactional
    public void appendStep(long runId, StepRow row) {
        jdbc.update("""
                    INSERT INTO t_agent_step (run_id, step_no, phase, tool_name, args_hash, result_hash, llm_tokens, latency_ms, decision)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, runId, row.stepNo(), row.phase(), row.tool(), row.argsHash(), row.resultHash(),
                row.llmTokens(), row.latencyMs(), row.decision());
    }

    public List<Map<String, Object>> replaySteps(long runId) {
        return jdbc.queryForList(
                "SELECT step_no, phase, tool_name, args_hash, result_hash, llm_tokens, latency_ms, decision, created_at "
                        + "FROM t_agent_step WHERE run_id = ? ORDER BY step_no", runId);
    }

    /* ---------- JSON 帮助 ---------- */

    private static String writeJson(Object v) {
        try {
            return JSON.writeValueAsString(v);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    private static Map<String, Object> readJson(String json) {
        if (json == null) {
            return Map.of();
        }
        try {
            return JSON.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("JSON 反序列化失败", e);
        }
    }

    /* ---------- 删除级联（冲刺2）---------- */

    /**
     * 删除租户所有 Agent 运行记录（级联步骤）
     */
    public void deleteByTenant(String tenantId) {
        // 步骤记录已在父级表中联级删除
        jdbc.update("DELETE FROM t_agent_run WHERE tenant_id = ?", tenantId);
    }

    /**
     * 删除指定用户的 Agent 运行记录（级联步骤）
     */
    public void deleteByTenantAndUser(String tenantId, String userId) {
        jdbc.update("DELETE FROM t_agent_run WHERE tenant_id = ? AND user_id = ?", tenantId, userId);
    }
}