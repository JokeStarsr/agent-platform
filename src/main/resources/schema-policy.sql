-- 差旅政策规则表（docs/design/architecture/20260901-w8-trip-scenario.md §2.1/§4.1）
-- 幂等 DDL + 种子规则，随 spring.sql.init.mode=always 每次启动执行
CREATE TABLE IF NOT EXISTS t_policy_rule (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       VARCHAR(64)  NOT NULL,
    rule_code       VARCHAR(64)  NOT NULL,
    dimension       VARCHAR(32)  NOT NULL,   -- flight_class / hotel_star / book_ahead / amount
    operator        VARCHAR(16)  NOT NULL,   -- in / le / ge / deny
    threshold_value VARCHAR(64)  NOT NULL,
    message         VARCHAR(255) NOT NULL,
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    source          VARCHAR(255),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, rule_code)
);
CREATE INDEX IF NOT EXISTS idx_policy_tenant_dim ON t_policy_rule (tenant_id, dimension);

-- 种子规则（演示用，业务规则由业务侧按政策文档补充；ON CONFLICT 幂等）
INSERT INTO t_policy_rule (tenant_id, rule_code, dimension, operator, threshold_value, message, source)
VALUES
  ('default', 'FLIGHT_CLASS',    'flight_class', 'in',   '经济舱',      '差旅仅允许经济舱（超舱位需特批）', '出差政策v1-舱位'),
  ('default', 'HOTEL_STAR',      'hotel_star',   'le',   '3',           '酒店星级不得超过三星',             '出差政策v1-住宿'),
  ('default', 'BOOK_AHEAD',      'book_ahead',   'ge',   '2',           '抢票需提前 2 天以上预订',           '出差政策v1-预订'),
  ('default', 'AMOUNT_LIMIT',    'amount',       'le',   '5000.00',     '单次行程总金额不得超过 5000 元，超出需审批', '出差政策v1-金额')
ON CONFLICT (tenant_id, rule_code) DO NOTHING;