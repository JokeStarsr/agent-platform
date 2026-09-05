-- L3 多智能体：根运行表 + 子 run 父指针（docs/design/architecture/20260905-multi-agent.md §12.4 / docs/design/table/20260905-t-multi-agent.md）
CREATE TABLE IF NOT EXISTS t_multi_agent_run (
    id           BIGSERIAL PRIMARY KEY,
    tenant_id    VARCHAR(64)  NOT NULL,
    topology     VARCHAR(16)  NOT NULL DEFAULT 'supervisor',
    task         TEXT,
    app_id       VARCHAR(64)  NOT NULL,
    stages_json  JSONB,
    plan_json    JSONB,
    status       VARCHAR(16)  NOT NULL DEFAULT 'PLANNING',
    final_answer TEXT,
    total_token  INT          NOT NULL DEFAULT 0,
    budget_limit INT          NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_multi_tenant_created ON t_multi_agent_run (tenant_id, created_at);

-- 子 run 父指针（普通 run 为 NULL）
ALTER TABLE t_agent_run ADD COLUMN IF NOT EXISTS parent_run_id BIGINT;
CREATE INDEX IF NOT EXISTS idx_agent_run_parent ON t_agent_run (parent_run_id);