package com.agent.data.workflow;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * L7 数据层：Workflow 引擎持久化（t_workflow_instance / t_workflow_node_run）
 * 对应 docs/design/architecture/20260831-workflow-engine.md §4。
 * <p>依赖只允许向下（同 W4 AgentRunRepository）。记录用 Java 记录体，变量/快照以 JSON 文本落库。</p>
 */
@Repository
public class WorkflowRepository {

    private final JdbcTemplate jdbc;

    public WorkflowRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /* ---------- t_workflow_instance ---------- */

    public record InstanceRow(long instanceId, String tenantId, String appId, String flowId, String flowDef,
                              String status, String input, String variables, String compensation,
                              String currentNodeIds, String traceId, String errorMsg,
                              Instant createdAt, Instant startedAt, Instant finishedAt) {
    }

    private static final RowMapper<InstanceRow> INSTANCE_ROW = (rs, i) -> new InstanceRow(
            rs.getLong("instance_id"),
            rs.getString("tenant_id"),
            rs.getString("app_id"),
            rs.getString("flow_id"),
            rs.getString("flow_def"),
            rs.getString("status"),
            rs.getString("input"),
            rs.getString("variables"),
            rs.getString("compensation"),
            rs.getString("current_node_ids"),
            rs.getString("trace_id"),
            rs.getString("error_msg"),
            toInstant(rs, "created_at"),
            toInstant(rs, "started_at"),
            toInstant(rs, "finished_at"));

    private static Instant toInstant(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        OffsetDateTime v = rs.getObject(col, OffsetDateTime.class);
        return v == null ? null : v.toInstant();
    }

    public long createInstance(String tenantId, String appId, String flowId, String flowDef,
                               String input, String traceId) {
        jdbc.update("""
                  INSERT INTO t_workflow_instance
                    (tenant_id, app_id, flow_id, flow_def, status, input, variables, trace_id)
                  VALUES (?, ?, ?, ?, 'CREATED', ?, '{}', ?)
                  """, tenantId, appId, flowId, flowDef, input, traceId);
        Long id = jdbc.queryForObject("SELECT currval(pg_get_serial_sequence('t_workflow_instance', 'instance_id'))", Long.class);
        return id;
    }

    public Optional<InstanceRow> findInstance(long instanceId) {
        return jdbc.query("SELECT * FROM t_workflow_instance WHERE instance_id = ?", INSTANCE_ROW, instanceId)
                .stream().findFirst();
    }

    /** 实例详情（控制器/审计展示） */
    public Map<String, Object> instanceDetail(long instanceId) {
        InstanceRow r = findInstance(instanceId).orElse(null);
        if (r == null) {
            return null;
        }
        Map<String, Object> d = new java.util.LinkedHashMap<>();
        d.put("instanceId", r.instanceId());
        d.put("tenantId", r.tenantId());
        d.put("appId", r.appId());
        d.put("flowId", r.flowId());
        d.put("status", r.status());
        d.put("variables", r.variables());
        d.put("compensation", r.compensation());
        d.put("currentNodeIds", r.currentNodeIds());
        d.put("traceId", r.traceId());
        d.put("errorMsg", r.errorMsg());
        d.put("createdAt", r.createdAt() == null ? null : r.createdAt().toString());
        d.put("startedAt", r.startedAt() == null ? null : r.startedAt().toString());
        d.put("finishedAt", r.finishedAt() == null ? null : r.finishedAt().toString());
        return d;
    }

    /** 原子提交：状态 + 变量快照 + 活动节点集合（节点完成时调用；崩溃恢复以 status+variables 为准） */
    public void commitInstance(long instanceId, String status, String variables,
                               String currentNodeIds, String errorMsg) {
        jdbc.update("""
                  UPDATE t_workflow_instance
                     SET status = ?, variables = ?, current_node_ids = ?, error_msg = ?
                   WHERE instance_id = ?
                  """, status, variables, currentNodeIds, errorMsg, instanceId);
    }

    /** 仅更新状态（取消/升级扫描标记等） */
    public void updateInstanceStatus(long instanceId, String status, String errorMsg) {
        jdbc.update("UPDATE t_workflow_instance SET status = ?, error_msg = ? WHERE instance_id = ?",
                status, errorMsg, instanceId);
    }

    /** 补偿清单原子更新 + finished_at 落地（流程终结） */
    public void finishInstance(long instanceId, String status, String compensation, String errorMsg) {
        jdbc.update("""
                  UPDATE t_workflow_instance
                     SET status = ?, compensation = ?, error_msg = ?, finished_at = now()
                   WHERE instance_id = ?
                  """, status, compensation, errorMsg, instanceId);
    }

    /** 启动恢复扫描：所有 RUNNING 实例（WAITING_APPROVAL 留给升级扫描器，仍挂起不恢复） */
    public List<Long> findRunningInstances() {
        return jdbc.query("SELECT instance_id FROM t_workflow_instance WHERE status = 'RUNNING' ORDER BY started_at",
                (rs, i) -> rs.getLong("instance_id"));
    }

    /** 实例列表行（管理台展示，只读视图） */
    public record InstanceListItem(long instanceId, String appId, String flowId, String status,
                                   String errorMsg, Instant createdAt, Instant finishedAt) {
    }

    private static final RowMapper<InstanceListItem> INSTANCE_LIST_ITEM = (rs, i) -> new InstanceListItem(
            rs.getLong("instance_id"),
            rs.getString("app_id"),
            rs.getString("flow_id"),
            rs.getString("status"),
            rs.getString("error_msg"),
            toInstant(rs, "created_at"),
            toInstant(rs, "finished_at"));

    private static final int MAX_PAGE_SIZE = 100;

    /** 实例总数（租户范围，可选状态筛选） */
    public long countInstances(String tenantId, String status) {
        StringBuilder sql = new StringBuilder("SELECT count(*) FROM t_workflow_instance WHERE tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        Long n = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return n == null ? 0 : n;
    }

    /** 分页实例列表（走 idx_wf_inst_tenant，page 从 1 起） */
    public List<InstanceListItem> pageInstances(String tenantId, int page, int size, String status) {
        int p = Math.max(page, 1);
        int sz = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        StringBuilder sql = new StringBuilder("""
                SELECT instance_id, app_id, flow_id, status, error_msg, created_at, finished_at
                FROM t_workflow_instance WHERE tenant_id = ?
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
        return jdbc.query(sql.toString(), INSTANCE_LIST_ITEM, args.toArray());
    }

    /** 补偿清单增量持久化（引擎在写节点成功后登记，崩溃/重启不丢） */
    public void updateCompensation(long instanceId, String compensationJson) {
        jdbc.update("UPDATE t_workflow_instance SET compensation = ? WHERE instance_id = ?",
                compensationJson, instanceId);
    }

    /* ---------- t_workflow_node_run ---------- */

    public record NodeRow(long nodeRunId, long instanceId, String nodeId, String nodeType, String parentNodeId,
                          int attempt, String status, String inputSnapshot, String outputSnapshot,
                          String idempotencyKey, Instant escalationAt, Instant escalatedAt,
                          String errorMsg, Instant startedAt, Instant finishedAt) {
        public List<String> inputArgs() {
            return List.of();
        }
    }

    private static final RowMapper<NodeRow> NODE_ROW = (rs, i) -> new NodeRow(
            rs.getLong("node_run_id"),
            rs.getLong("instance_id"),
            rs.getString("node_id"),
            rs.getString("node_type"),
            rs.getString("parent_node_id"),
            rs.getInt("attempt"),
            rs.getString("status"),
            rs.getString("input_snapshot"),
            rs.getString("output_snapshot"),
            rs.getString("idempotency_key"),
            toInstant(rs, "escalation_at"),
            toInstant(rs, "escalated_at"),
            rs.getString("error_msg"),
            toInstant(rs, "started_at"),
            toInstant(rs, "finished_at"));

    /** 节点行创建（默认 PENDING，started_at=now） */
    public long createNodeRun(long instanceId, String nodeId, String nodeType, String parentNodeId, int attempt) {
        jdbc.update("""
                  INSERT INTO t_workflow_node_run
                    (instance_id, node_id, node_type, parent_node_id, attempt, status)
                  VALUES (?, ?, ?, ?, ?, 'PENDING')
                  """, instanceId, nodeId, nodeType, parentNodeId, attempt);
        Long id = jdbc.queryForObject("SELECT currval(pg_get_serial_sequence('t_workflow_node_run', 'node_run_id'))", Long.class);
        return id;
    }

    public Optional<NodeRow> findNode(long nodeRunId) {
        return jdbc.query("SELECT * FROM t_workflow_node_run WHERE node_run_id = ?", NODE_ROW, nodeRunId)
                .stream().findFirst();
    }

    public void markNodeRunning(long nodeRunId) {
        jdbc.update("UPDATE t_workflow_node_run SET status = 'RUNNING' WHERE node_run_id = ?", nodeRunId);
    }

    /** 人工节点挂起：写 WAITING_APPROVAL + 升级截止时点 + 输入快照 */
    public void hangNodeForApproval(long nodeRunId, Instant escalationAt, String inputSnapshot) {
        jdbc.update("""
                  UPDATE t_workflow_node_run
                     SET status = 'WAITING_APPROVAL', escalation_at = ?, input_snapshot = ?
                   WHERE node_run_id = ?
                  """, java.sql.Timestamp.from(escalationAt), inputSnapshot, nodeRunId);
    }

    /** 完成/失败/跳过：原子写状态 + 输出快照 + 错误 + finished_at */
    public void finishNode(long nodeRunId, String status, String outputSnapshot, String errorMsg) {
        jdbc.update("""
                  UPDATE t_workflow_node_run
                     SET status = ?, output_snapshot = ?, error_msg = ?, finished_at = now()
                   WHERE node_run_id = ?
                  """, status, outputSnapshot, errorMsg, nodeRunId);
    }

    /** 绑定写操作幂等键（TOOL 写节点） */
    public void bindIdempotencyKey(long nodeRunId, String idempotencyKey) {
        jdbc.update("UPDATE t_workflow_node_run SET idempotency_key = ? WHERE node_run_id = ?",
                idempotencyKey, nodeRunId);
    }

    /** 指定节点的全部执行记录（按 attempt 升序，供重试/历史） */
    public List<NodeRow> nodeAttempts(long instanceId, String nodeId) {
        return jdbc.query("SELECT * FROM t_workflow_node_run WHERE instance_id = ? AND node_id = ? ORDER BY attempt",
                NODE_ROW, instanceId, nodeId);
    }

    /** 实例所有节点记录（恢复/历史） */
    public List<NodeRow> nodeRuns(long instanceId) {
        return jdbc.query("SELECT * FROM t_workflow_node_run WHERE instance_id = ? ORDER BY node_run_id",
                NODE_ROW, instanceId);
    }

    /** 人工节点超时升级扫描：未升级的 WAITING_APPROVAL 节点 */
    public List<NodeRow> findEscalationDue(Instant now) {
        return jdbc.query("""
                  SELECT * FROM t_workflow_node_run
                   WHERE status = 'WAITING_APPROVAL'
                     AND escalation_at IS NOT NULL AND escalation_at < ?
                     AND escalated_at IS NULL
                  ORDER BY escalation_at
                  """, NODE_ROW, java.sql.Timestamp.from(now));
    }

    public void markEscalated(long nodeRunId) {
        jdbc.update("UPDATE t_workflow_node_run SET escalated_at = now() WHERE node_run_id = ?", nodeRunId);
    }
}