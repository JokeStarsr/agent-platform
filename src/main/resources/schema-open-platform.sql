-- W17 开放平台：API Key 认证 + 租户配额表

-- API Key 表（存储哈希而非明文，安全优先）
CREATE TABLE IF NOT EXISTS t_api_key (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       VARCHAR(64) NOT NULL,
    api_key_prefix  VARCHAR(8) NOT NULL,   -- API Key 前 8 位（用于展示，如 sk-abc123...）
    api_key_hash    VARCHAR(64) NOT NULL,  -- SHA-256 哈希（存储哈希而非明文）
    name            VARCHAR(128) NOT NULL,  -- API Key 名称（如"生产环境"）
    status          VARCHAR(16) NOT NULL DEFAULT 'active',  -- active/expired/disabled
    expires_at      TIMESTAMPTZ,  -- 过期时间（NULL 表示永不过期）
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_api_key_hash ON t_api_key(api_key_hash);
CREATE INDEX IF NOT EXISTS idx_api_key_tenant ON t_api_key(tenant_id);

-- 租户配额表（限流 + 预算）
CREATE TABLE IF NOT EXISTS t_tenant_quota (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       VARCHAR(64) NOT NULL UNIQUE,
    qps             INT NOT NULL DEFAULT 10,  -- 每秒请求数上限
    daily_token_quota BIGINT NOT NULL DEFAULT 100000,  -- 日 Token 配额
    daily_budget    DECIMAL(10,2) NOT NULL DEFAULT 100.00,  -- 日预算（元）
    budget_exceeded BOOLEAN NOT NULL DEFAULT FALSE,  -- 预算是否超支（熔断标记）
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_tenant_quota_tenant ON t_tenant_quota(tenant_id);

-- Token 用量明细表（按月分区，保留完整调用记录）
CREATE TABLE IF NOT EXISTS t_token_usage (
    id              BIGSERIAL,
    trace_id        VARCHAR(64) NOT NULL,
    tenant_id       VARCHAR(64) NOT NULL,
    app_id          VARCHAR(64),
    model_name      VARCHAR(64) NOT NULL,
    prompt_tokens   INT NOT NULL DEFAULT 0,
    completion_tokens INT NOT NULL DEFAULT 0,
    total_tokens    INT NOT NULL DEFAULT 0,
    cost            DECIMAL(10,4) NOT NULL DEFAULT 0,  -- 费用（元）
    duration_ms     BIGINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);

CREATE INDEX IF NOT EXISTS idx_token_usage_trace ON t_token_usage(trace_id);
CREATE INDEX IF NOT EXISTS idx_token_usage_tenant ON t_token_usage(tenant_id, created_at DESC);

-- Token 用量日聚合表（加速看板查询）
CREATE TABLE IF NOT EXISTS t_token_usage_daily (
    id              BIGSERIAL PRIMARY KEY,
    stat_date       DATE NOT NULL,
    tenant_id       VARCHAR(64) NOT NULL,
    app_id          VARCHAR(64),
    model_name      VARCHAR(64) NOT NULL,
    total_calls     BIGINT NOT NULL DEFAULT 0,
    total_tokens    BIGINT NOT NULL DEFAULT 0,
    total_cost      DECIMAL(10,2) NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (stat_date, tenant_id, app_id, model_name)
);

CREATE INDEX IF NOT EXISTS idx_token_usage_daily_tenant ON t_token_usage_daily(tenant_id, stat_date DESC);

-- 预算告警历史表（去重：同一租户同一档位每日仅告警一次）
CREATE TABLE IF NOT EXISTS t_budget_alert_history (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       VARCHAR(64) NOT NULL,
    alert_type      VARCHAR(16) NOT NULL,  -- warning(80%)/critical(100%)
    usage_rate      DECIMAL(5,2) NOT NULL,  -- 使用率（如 85.5）
    today_usage     DECIMAL(10,2) NOT NULL,
    daily_budget    DECIMAL(10,2) NOT NULL,
    notified_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_budget_alert_tenant ON t_budget_alert_history(tenant_id, notified_at DESC);

-- 创建当月分区（每月需手动创建下月分区，或由定时任务自动创建）
CREATE TABLE IF NOT EXISTS t_token_usage_y2026m09 PARTITION OF t_token_usage
    FOR VALUES FROM ('2026-09-01') TO ('2026-10-01');

-- 种子数据：默认租户配额（可根据实际需求调整）
INSERT INTO t_tenant_quota (tenant_id, qps, daily_token_quota, daily_budget)
VALUES ('default', 10, 100000, 100.00)
ON CONFLICT (tenant_id) DO NOTHING;
