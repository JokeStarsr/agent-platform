-- L4 Data Agent: Schema 语义层元数据表 + 演示业务表（docs/design/architecture/20260910-data-agent.md §3）
-- 幂等 DDL + 种子数据，随 spring.sql.init.mode=always 每次启动执行

-- ========================================
-- 演示业务表（Data Agent NL2SQL 查询目标）
-- ========================================

-- 客户表
CREATE TABLE IF NOT EXISTS t_customer (
    customer_id   BIGSERIAL PRIMARY KEY,
    name          VARCHAR(128) NOT NULL,
    level         VARCHAR(16)  NOT NULL DEFAULT 'bronze',  -- bronze/silver/gold/platinum
    region        VARCHAR(32)  NOT NULL,                   -- 华东/华南/华北/西南
    email         VARCHAR(128),
    phone         VARCHAR(32),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_customer_level  ON t_customer (level);
CREATE INDEX IF NOT EXISTS idx_customer_region ON t_customer (region);

-- 产品表
CREATE TABLE IF NOT EXISTS t_product (
    product_id    BIGSERIAL PRIMARY KEY,
    name          VARCHAR(128) NOT NULL,
    category      VARCHAR(32)  NOT NULL,  -- 电子产品/服装/食品/图书/家居
    price         DECIMAL(10,2) NOT NULL,
    stock         INT          NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_product_category ON t_product (category);

-- 订单表
CREATE TABLE IF NOT EXISTS t_order (
    order_id      BIGSERIAL PRIMARY KEY,
    customer_id   BIGINT       NOT NULL REFERENCES t_customer(customer_id),
    amount        DECIMAL(10,2) NOT NULL,
    status        VARCHAR(16)  NOT NULL DEFAULT 'pending',  -- pending/paid/shipped/completed/cancelled
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_order_customer ON t_order (customer_id);
CREATE INDEX IF NOT EXISTS idx_order_status   ON t_order (status);
CREATE INDEX IF NOT EXISTS idx_order_created  ON t_order (created_at);

-- 订单明细表
CREATE TABLE IF NOT EXISTS t_order_item (
    item_id       BIGSERIAL PRIMARY KEY,
    order_id      BIGINT       NOT NULL REFERENCES t_order(order_id),
    product_id    BIGINT       NOT NULL REFERENCES t_product(product_id),
    quantity      INT          NOT NULL,
    unit_price    DECIMAL(10,2) NOT NULL,
    subtotal      DECIMAL(10,2) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_order_item_order   ON t_order_item (order_id);
CREATE INDEX IF NOT EXISTS idx_order_item_product ON t_order_item (product_id);

-- ========================================
-- Schema 语义层元数据表
-- ========================================

CREATE TABLE IF NOT EXISTS t_schema_metadata (
    id              BIGSERIAL PRIMARY KEY,
    table_name      VARCHAR(64)  NOT NULL,
    table_comment   VARCHAR(256) NOT NULL,
    column_name     VARCHAR(64)  NOT NULL,
    column_comment  VARCHAR(256) NOT NULL,
    column_type     VARCHAR(64)  NOT NULL,
    is_nullable     BOOLEAN      NOT NULL DEFAULT FALSE,
    foreign_key     VARCHAR(128),  -- 格式：table.column（如 t_customer.customer_id）
    common_metrics  JSONB,         -- 常用口径（如 {"total_sales": "SUM(amount)"}）
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (table_name, column_name)
);
CREATE INDEX IF NOT EXISTS idx_schema_metadata_table ON t_schema_metadata (table_name);

-- ========================================
-- 种子数据：Schema 语义层（5 表元数据）
-- ========================================

INSERT INTO t_schema_metadata (table_name, table_comment, column_name, column_comment, column_type, is_nullable, foreign_key, common_metrics)
VALUES
  -- t_customer（客户表）
  ('t_customer', '客户表', 'customer_id', '客户ID（主键）', 'BIGINT', FALSE, NULL, NULL),
  ('t_customer', '客户表', 'name', '客户名称', 'VARCHAR(128)', FALSE, NULL, NULL),
  ('t_customer', '客户表', 'level', '客户等级（bronze/silver/gold/platinum）', 'VARCHAR(16)', FALSE, NULL, NULL),
  ('t_customer', '客户表', 'region', '地区（华东/华南/华北/西南）', 'VARCHAR(32)', FALSE, NULL, NULL),
  ('t_customer', '客户表', 'email', '邮箱', 'VARCHAR(128)', TRUE, NULL, NULL),
  ('t_customer', '客户表', 'phone', '电话', 'VARCHAR(32)', TRUE, NULL, NULL),
  ('t_customer', '客户表', 'created_at', '创建时间', 'TIMESTAMPTZ', FALSE, NULL, NULL),
  ('t_customer', '客户表', 'updated_at', '更新时间', 'TIMESTAMPTZ', FALSE, NULL, NULL),

  -- t_product（产品表）
  ('t_product', '产品表', 'product_id', '产品ID（主键）', 'BIGINT', FALSE, NULL, NULL),
  ('t_product', '产品表', 'name', '产品名称', 'VARCHAR(128)', FALSE, NULL, NULL),
  ('t_product', '产品表', 'category', '产品类别（电子产品/服装/食品/图书/家居）', 'VARCHAR(32)', FALSE, NULL, NULL),
  ('t_product', '产品表', 'price', '产品单价（元）', 'DECIMAL(10,2)', FALSE, NULL, NULL),
  ('t_product', '产品表', 'stock', '库存数量', 'INT', FALSE, NULL, NULL),
  ('t_product', '产品表', 'created_at', '创建时间', 'TIMESTAMPTZ', FALSE, NULL, NULL),
  ('t_product', '产品表', 'updated_at', '更新时间', 'TIMESTAMPTZ', FALSE, NULL, NULL),

  -- t_order（订单表）
  ('t_order', '订单表', 'order_id', '订单ID（主键）', 'BIGINT', FALSE, NULL, '{"total_sales": "SUM(amount) WHERE status IN (''paid'', ''shipped'', ''completed'')", "avg_order_value": "AVG(amount)"}'),
  ('t_order', '订单表', 'customer_id', '客户ID（外键 → t_customer.customer_id）', 'BIGINT', FALSE, 't_customer.customer_id', NULL),
  ('t_order', '订单表', 'amount', '订单金额（元）', 'DECIMAL(10,2)', FALSE, NULL, NULL),
  ('t_order', '订单表', 'status', '订单状态（pending/paid/shipped/completed/cancelled）', 'VARCHAR(16)', FALSE, NULL, NULL),
  ('t_order', '订单表', 'created_at', '创建时间', 'TIMESTAMPTZ', FALSE, NULL, NULL),
  ('t_order', '订单表', 'updated_at', '更新时间', 'TIMESTAMPTZ', FALSE, NULL, NULL),

  -- t_order_item（订单明细表）
  ('t_order_item', '订单明细表', 'item_id', '明细ID（主键）', 'BIGINT', FALSE, NULL, NULL),
  ('t_order_item', '订单明细表', 'order_id', '订单ID（外键 → t_order.order_id）', 'BIGINT', FALSE, 't_order.order_id', NULL),
  ('t_order_item', '订单明细表', 'product_id', '产品ID（外键 → t_product.product_id）', 'BIGINT', FALSE, 't_product.product_id', NULL),
  ('t_order_item', '订单明细表', 'quantity', '购买数量', 'INT', FALSE, NULL, NULL),
  ('t_order_item', '订单明细表', 'unit_price', '单价（元）', 'DECIMAL(10,2)', FALSE, NULL, NULL),
  ('t_order_item', '订单明细表', 'subtotal', '小计金额（元）', 'DECIMAL(10,2)', FALSE, NULL, '{"total_sales": "SUM(subtotal)"}'),
  ('t_order_item', '订单明细表', 'created_at', '创建时间', 'TIMESTAMPTZ', FALSE, NULL, NULL),

  -- t_policy_rule（差旅政策表，已有表结构，补语义层）
  ('t_policy_rule', '差旅政策规则表', 'id', '规则ID（主键）', 'BIGINT', FALSE, NULL, NULL),
  ('t_policy_rule', '差旅政策规则表', 'tenant_id', '租户ID', 'VARCHAR(64)', FALSE, NULL, NULL),
  ('t_policy_rule', '差旅政策规则表', 'rule_code', '规则编码', 'VARCHAR(64)', FALSE, NULL, NULL),
  ('t_policy_rule', '差旅政策规则表', 'dimension', '规则维度（flight_class/hotel_star/book_ahead/amount）', 'VARCHAR(32)', FALSE, NULL, NULL),
  ('t_policy_rule', '差旅政策规则表', 'operator', '操作符（in/le/ge/deny）', 'VARCHAR(16)', FALSE, NULL, NULL),
  ('t_policy_rule', '差旅政策规则表', 'threshold_value', '阈值', 'VARCHAR(64)', FALSE, NULL, NULL),
  ('t_policy_rule', '差旅政策规则表', 'message', '提示信息', 'VARCHAR(255)', FALSE, NULL, NULL),
  ('t_policy_rule', '差旅政策规则表', 'enabled', '是否启用', 'BOOLEAN', FALSE, NULL, NULL),
  ('t_policy_rule', '差旅政策规则表', 'source', '来源', 'VARCHAR(255)', TRUE, NULL, NULL),
  ('t_policy_rule', '差旅政策规则表', 'created_at', '创建时间', 'TIMESTAMPTZ', FALSE, NULL, NULL),
  ('t_policy_rule', '差旅政策规则表', 'updated_at', '更新时间', 'TIMESTAMPTZ', FALSE, NULL, NULL)
ON CONFLICT (table_name, column_name) DO NOTHING;
