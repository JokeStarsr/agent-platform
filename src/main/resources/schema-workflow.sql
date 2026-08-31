-- Workflow 引擎表结构（docs/design/architecture/20260831-workflow-engine.md §4）
-- 幂等 DDL，随 spring.sql.init.mode=always 每次启动执行
CREATE TABLE IF NOT EXISTS t_workflow_instance (
    instance_id      BIGSERIAL PRIMARY KEY,
    tenant_id        VARCHAR(64)  NOT NULL,
    app_id           VARCHAR(64)  NOT NULL,
    flow_id          VARCHAR(64)  NOT NULL,
    flow_def         TEXT         NOT NULL,
    status           VARCHAR(24)  NOT NULL,
    input            TEXT         NOT NULL,
    -- 变量快照 JSON（断点恢复权威源，每节点提交后整量更新）
    variables        TEXT         NOT NULL,
    -- 补偿清单 JSON（已完成写节点待回滚，§2.6）
    compensation     TEXT         NOT NULL DEFAULT '[]',
    -- 活动节点 id JSON 数组（RUNNING 时恢复入口）
    current_node_ids TEXT         NOT NULL DEFAULT '[]',
    trace_id         VARCHAR(64),
    error_msg        TEXT,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    started_at       TIMESTAMPTZ,
    finished_at      TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_wf_inst_tenant ON t_workflow_instance (tenant_id, created_at);
CREATE INDEX IF NOT EXISTS idx_wf_inst_status ON t_workflow_instance (status, created_at);

CREATE TABLE IF NOT EXISTS t_workflow_node_run (
    node_run_id      BIGSERIAL PRIMARY KEY,
    instance_id      BIGINT       NOT NULL,   -- 逻辑外键 → t_workflow_instance（同 W4 惯例不建物理外键）
    node_id          VARCHAR(64)  NOT NULL,
    node_type        VARCHAR(16)  NOT NULL,   -- LLM/TOOL/CONDITION/HUMAN/PARALLEL/SUBFLOW
    parent_node_id   VARCHAR(64),             -- 嵌套归属（并行分支/子流程，空=顶层）
    attempt          INT          NOT NULL DEFAULT 1,
    status           VARCHAR(24)  NOT NULL,   -- PENDING/RUNNING/WAITING_APPROVAL/COMPLETED/FAILED/SKIPPED/CANCELED
    input_snapshot   TEXT,
    output_snapshot  TEXT,
    idempotency_key  VARCHAR(128),            -- TOOL 写节点幂等键（重试/补偿复用）
    escalation_at    TIMESTAMPTZ,             -- 人工节点超时点（WAITING_APPROVAL 时非空）
    escalated_at     TIMESTAMPTZ,             -- 已升级时间（防重复升级+回调幂等）
    error_msg        TEXT,
    started_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at      TIMESTAMPTZ,
    UNIQUE (instance_id, node_id, attempt)
);
CREATE INDEX IF NOT EXISTS idx_wf_node_inst ON t_workflow_node_run (instance_id);
CREATE INDEX IF NOT EXISTS idx_wf_node_pending ON t_workflow_node_run (status, escalation_at);