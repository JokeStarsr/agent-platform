-- 长期记忆表结构（docs/design/architecture/20260901-memory-context.md §4.1）
-- 幂等 DDL，随 spring.sql.init.mode=always 每次启动执行
CREATE TABLE IF NOT EXISTS t_user_memory (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   VARCHAR(64)  NOT NULL,   -- 租户隔离（贯穿所有查询）
    user_id     VARCHAR(64)  NOT NULL,   -- 用户隔离（越权红线字段）
    field       VARCHAR(32)  NOT NULL,   -- preference / identity / verified_fact / frequent_ask
    value       TEXT         NOT NULL,
    confidence  DOUBLE PRECISION NOT NULL DEFAULT 0.9,
    status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE / PENDING_CONFIRM / DELETED
    source      TEXT,                                    -- 来源会话/文档（血缘）
    embedding   VECTOR(1024),                            -- 语义向量（智谱 embedding，可空=降级按近期召回）
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_mem_user_status ON t_user_memory (tenant_id, user_id, status);
CREATE INDEX IF NOT EXISTS idx_mem_pending ON t_user_memory (tenant_id, user_id, status)
    WHERE status = 'PENDING_CONFIRM';
CREATE INDEX IF NOT EXISTS idx_mem_embedding ON t_user_memory USING hnsw (embedding vector_cosine_ops);