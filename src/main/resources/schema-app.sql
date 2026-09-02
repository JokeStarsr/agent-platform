-- 应用注册表（docs/design/table/20260902-app-table.md）
-- 幂等 DDL，随 spring.sql.init.mode=always 每次启动执行
CREATE TABLE IF NOT EXISTS t_app (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   VARCHAR(64)  NOT NULL DEFAULT 'GLOBAL',
    app_id      VARCHAR(64)  NOT NULL,
    name        VARCHAR(128) NOT NULL,
    status      VARCHAR(16)  NOT NULL DEFAULT 'CREATED',
    config_json JSONB        NOT NULL,
    version     INT          NOT NULL DEFAULT 1,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, app_id)
);