-- W16 Step 3: 数据血缘记录表
-- 记录每次查询涉及的表、列、SQL、时间戳，支持数据溯源

CREATE TABLE IF NOT EXISTS t_data_lineage (
    id              BIGSERIAL PRIMARY KEY,
    trace_id        VARCHAR(64) NOT NULL,              -- 请求追踪 ID
    tenant_id       VARCHAR(64) NOT NULL,              -- 租户 ID
    question        TEXT NOT NULL,                     -- 用户问题
    generated_sql   TEXT NOT NULL,                     -- 生成的 SQL
    tables_used     JSONB NOT NULL DEFAULT '[]',       -- 涉及的表列表 ["table1", "table2"]
    columns_used    JSONB NOT NULL DEFAULT '[]',       -- 涉及的列列表 [{"table": "t1", "column": "c1"}]
    result_rows     INT NOT NULL DEFAULT 0,            -- 返回行数
    chart_type      VARCHAR(32),                       -- 图表类型 (line/bar/pie/kpi/table)
    analysis_text   TEXT,                              -- 分析文本
    verification    JSONB,                             -- 验证结果 {"all_passed": true, "details": [...]}
    duration_ms     BIGINT NOT NULL DEFAULT 0,         -- 总耗时（毫秒）
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 索引：按租户查询
CREATE INDEX IF NOT EXISTS idx_data_lineage_tenant ON t_data_lineage(tenant_id, created_at DESC);

-- 索引：按追踪 ID 查询
CREATE INDEX IF NOT EXISTS idx_data_lineage_trace ON t_data_lineage(trace_id);

-- 索引：按表名查询（支持反向溯源：哪些查询用了某张表）
CREATE INDEX IF NOT EXISTS idx_data_lineage_tables ON t_data_lineage USING GIN(tables_used);
