-- L5 工具市场：工具目录表（docs/design/architecture/20260905-tool-marketplace.md §3 / docs/design/table/20260905-t-tool-catalog.md）
-- 平台级共享目录（工具是平台资产非租户资产）；调用授权由 t_tool_grant 独立管控
CREATE TABLE IF NOT EXISTS t_tool_catalog (
    id                 BIGSERIAL PRIMARY KEY,
    tool_name          VARCHAR(64)  NOT NULL,
    version            INT          NOT NULL DEFAULT 1,
    display_name       VARCHAR(128) NOT NULL,
    description        VARCHAR(512) NOT NULL,
    category           VARCHAR(32)  NOT NULL DEFAULT 'utility',
    parameters         JSONB        NOT NULL,
    permission         VARCHAR(16)  NOT NULL DEFAULT 'READ',
    source             VARCHAR(24)  NOT NULL DEFAULT 'SELF_REGISTERED',
    external_url       VARCHAR(256),
    external_tool_name VARCHAR(64),
    status             VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    enabled            BOOLEAN      NOT NULL DEFAULT TRUE,
    owner_id           VARCHAR(64)  NOT NULL DEFAULT 'platform',
    testcase_json      JSONB        NOT NULL DEFAULT '[]'::jsonb,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (tool_name, version)
);
CREATE INDEX IF NOT EXISTS idx_tool_catalog_status   ON t_tool_catalog (status, enabled);
CREATE INDEX IF NOT EXISTS idx_tool_catalog_category ON t_tool_catalog (category, status);