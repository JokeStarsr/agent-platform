-- Agent Runtime 表结构（docs/design/architecture/20260831-agent-runtime.md §4）
-- 幂等 DDL，随 spring.sql.init.mode=always 每次启动执行
CREATE TABLE IF NOT EXISTS t_agent_run (
    run_id           BIGSERIAL PRIMARY KEY,
    tenant_id        VARCHAR(64)  NOT NULL,
    app_id           VARCHAR(64)  NOT NULL,
    task             TEXT         NOT NULL,
    status           VARCHAR(24)  NOT NULL,
    max_steps        INT          NOT NULL,
    token_budget     INT          NOT NULL,
    timeout_ms       INT          NOT NULL,
    loop_threshold   INT          NOT NULL DEFAULT 3,
    steps_done       INT          NOT NULL DEFAULT 0,
    tokens_used      INT          NOT NULL DEFAULT 0,
    trace_id         VARCHAR(64),
    -- HITL 挂起（WAITING_APPROVAL 时才非空）：待审批的写工具名/参数 JSON/审批时限
    pending_tool     VARCHAR(64),
    pending_args     TEXT,
    -- 优雅终止：非 COMPLETED 时的未完成说明
    unfinished_reason TEXT,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    started_at       TIMESTAMPTZ,
    finished_at      TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_agent_run_tenant_created ON t_agent_run (tenant_id, created_at);
CREATE INDEX IF NOT EXISTS idx_agent_run_status_created ON t_agent_run (status, created_at);

CREATE TABLE IF NOT EXISTS t_agent_step (
    id          BIGSERIAL PRIMARY KEY,
    run_id      BIGINT       NOT NULL REFERENCES t_agent_run (run_id),
    step_no     INT          NOT NULL,
    phase       VARCHAR(16)  NOT NULL,
    tool_name   VARCHAR(64),
    args_hash   VARCHAR(64),
    result_hash VARCHAR(64),
    llm_tokens  INT          NOT NULL DEFAULT 0,
    latency_ms  INT          NOT NULL DEFAULT 0,
    decision    TEXT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (run_id, step_no)
);
CREATE INDEX IF NOT EXISTS idx_agent_step_run ON t_agent_step (run_id, step_no);

-- 工具调用幂等记录（docs/design/architecture/20260901-tool-engine.md §2.2）
CREATE TABLE IF NOT EXISTS t_tool_invocation (
    invocation_id   BIGSERIAL PRIMARY KEY,
    idempotency_key VARCHAR(128) NOT NULL,
    tenant_id       VARCHAR(64)  NOT NULL,
    tool_name       VARCHAR(64)  NOT NULL,
    args_hash       VARCHAR(64)  NOT NULL,
    result_payload  TEXT,
    status          VARCHAR(16)  NOT NULL,   -- IN_PROGRESS / SUCCESS / FAILED
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at     TIMESTAMPTZ,
    UNIQUE (idempotency_key)
);
CREATE INDEX IF NOT EXISTS idx_tool_invo_tenant_created ON t_tool_invocation (tenant_id, created_at);