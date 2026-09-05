-- L5 MCP 网关：租户级工具授权表（docs/design/architecture/20260904-mcp-gateway.md §5 / docs/design/table/20260904-t-tool-grant.md）
-- MCP 入站调用无 appId，授权判定从 per-app 白名单切到 per-tenant；deny-by-default（查无授权行即拒绝）
CREATE TABLE IF NOT EXISTS t_tool_grant (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   VARCHAR(64)  NOT NULL,
    tool_name   VARCHAR(64)  NOT NULL,
    permission  VARCHAR(16)  NOT NULL,
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    granted_by  VARCHAR(64)  NOT NULL DEFAULT 'admin_api',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, tool_name)
);

-- 商旅 6 工具 + 客服常用工具种子授权（保证 W10 检查点"商旅工具经 MCP 调用"可跑通）
INSERT INTO t_tool_grant (tenant_id, tool_name, permission, granted_by)
SELECT 'default', tool, perm, 'seed'
FROM (VALUES
    ('policy_query','READ'), ('compare_flight','READ'), ('compare_hotel','READ'),
    ('policy_check','READ'), ('book_order','WRITE'), ('pay_order','PAYMENT'),
    ('cancel_order','WRITE'), ('notify_user','WRITE'),
    ('query_recent_orders','READ'), ('send_coupon','WRITE'), ('refund_order_partial','WRITE')
) AS t(tool, perm)
ON CONFLICT (tenant_id, tool_name) DO NOTHING;
