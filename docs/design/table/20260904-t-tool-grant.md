# t_tool_grant 租户级工具授权表设计

> 版本：v1.1 ｜ 状态：**已实现**（2026-09-05 建表 + 种子数据已落 schema-mcp.sql） ｜ 依据：《开发排期》W10（MCP 网关 · 租户级工具授权）、架构设计 `docs/design/architecture/20260904-mcp-gateway.md`（W10）、CLAUDE.md 设计文档铁律

---

## 1. 基本信息

| 项 | 值 |
|----|----|
| 表名 | t_tool_grant |
| 所属数据域 | 工具（L5） |
| 所属架构层 | L7 数据层（`com.agent.data.toolgrant`） |
| 目标数据库 | PostgreSQL（同项目现有表，`spring.sql.init.mode=always` 幂等 DDL 启动执行） |
| 作者 / 日期 | 2026-09-04 |
| 状态 | 待检查 |

---

## 2. 设计目的

**要解决的问题**：W10 MCP 网关让外部 Agent 经 MCP 协议调用平台工具（`tools/call`）。外部 Agent **没有 appId**（不在 `t_app` 注册），现有 `AppToolGate` 的 per-app 白名单无法约束它——必须有一张**租户级**工具授权表，回答"这个租户能不能调这个工具"。检查点要求"未授权租户调用被拒且有审计记录"。

**没有它会怎样**：MCP 入站端点对任何租户开放全部工具，写操作（book_order）可被未授权租户调用，P3 阶段"未授权调用被拒"检查点无法达成，跨租户越权风险（红线领域）。

**设计原因**：授权关系是"租户 × 工具"多对多、需在线增删（授权/撤销 API）→ 落 DB（与 t_app 的"DB 为唯一权威"先例一致）；工具名引用 `ToolRegistry` 内存注册表，授权表存工具名即可（工具元数据不冗余存储，避免双源）。

---

## 3. 字段定义（逐项附说明）

| 字段 | 类型 | 可空 | 默认值 | 说明 |
|------|------|------|--------|------|
| id | BIGSERIAL | NO | 自增 | 主键 |
| tenant_id | VARCHAR(64) | NO | - | 租户维度；贯穿授权判定，**漏此字段=跨租户越权**（红线） |
| tool_name | VARCHAR(64) | NO | - | 工具名，引用 `ToolRegistry` 注册名（如 compare_flight）；**逻辑外键**，不建物理外键 |
| permission | VARCHAR(16) | NO | - | 快照该工具当前权限等级：READ / WRITE / PAYMENT（与 ToolMeta 一致；PAYMENT 恒 403，仅登记不放开） |
| enabled | BOOLEAN | NO | TRUE | 是否生效；撤销=置 FALSE（软撤销，保留授权历史），删除=物理删除 |
| granted_by | VARCHAR(64) | NO | - | 授权来源：admin_api / seed / 迁移 |
| created_at / updated_at | TIMESTAMPTZ | NO | now() | 时间戳 |

> **为什么 permission 冗余存快照**：工具权限可能在 `AgentTool.permission()` 演进（如 READ→WRITE），授权记录需反映授权时刻的语义，且审计可回溯"授权时该工具是什么权限"。判定时以本表 permission 为准，与 ToolMeta 冲突时告警（演进项，不阻塞 v1）。

---

## 4. 索引设计

| 索引名 | 字段 | 类型 | 支撑查询场景 |
|--------|------|------|-------------|
| uq_tool_grant_tenant_tool | (tenant_id, tool_name) | UNIQUE | 授权判定：`SELECT * FROM t_tool_grant WHERE tenant_id=? AND tool_name=? AND enabled=true`（MCP tools/call 热路径，单行命中） |
| idx_tool_grant_tenant | (tenant_id, enabled) | 普通 | 管理 API：某租户授权列表分页 `WHERE tenant_id=? AND enabled=?` |

> 热路径是 `(tenant_id, tool_name)` 精确查，唯一索引即覆盖；租户列表查询量小，普通索引足够。

---

## 5. 关联关系与约束

- 无物理外键（同项目惯例）。`tool_name` → `ToolRegistry`（内存注册表）逻辑外键；工具下线时授权记录保留但判定因工具未注册而自然 403。
- 与 `t_app.config_json.tools[]`（per-app 白名单）**并存不冲突**：平台内 Agent 走 per-app（`AppToolGate`）；MCP 入站走本表 per-tenant。两者独立判定，任一未授权即拒绝。

---

## 6. 租户隔离设计 ★必查项

| 项 | 内容 |
|----|------|
| 隔离方式 | 行级（`tenant_id` 字段） |
| 隔离字段 | `tenant_id`，贯穿所有授权查询 |
| 越权防护 | 授权判定以 **MDC 中 tenant_id** 为准（由 MCP 鉴权 Filter 从 `X-Tenant-Id` + 绑定 key 解析写入）；本表查询强制带 `tenant_id`，杜绝租户 A 命中租户 B 的授权行 |

---

## 7. 数据治理影响 ★必查项

| 项 | 结论 |
|----|------|
| 是否含 PII | 否（仅租户 × 工具授权关系，无个人数据） |
| 数据血缘 | 授权来源 `granted_by` 可回溯（admin_api / seed）；审计日志（ToolAuditService）可回溯到具体调用 |
| 保留策略 | 配置数据，随生命周期不删除；软撤销（enabled=false）保留历史 |
| 删除语义 | 撤销=逻辑删除（enabled=false）；物理删除仅用于误授权清理 |

---

## 8. 建表 SQL（设计批准后才执行）

```sql
-- 批准状态：已实现（schema-mcp.sql，2026-09-05）
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
```

---

## 9. 演进预判

- **量级**：租户数 × 工具数，个位数租户 × 数十工具，数百行级，无膨胀压力。
- **最先撑不住的点**：无（授权关系量级极小）。真正的演进点在**授权粒度**——出现"按工具参数/按角色细分授权"（如某租户只能 book_order 到 3 星酒店）时，本表需扩展为规则表（列加 `dimension/operator/threshold`，可复用 W8 `t_policy_rule` 的模式）或引入独立授权服务。
- 出现跨租户共享授权模板 → 抽 `grant_template`，本表加 `template_id` 引用。

---

## 10. 检查记录

| 日期 | 检查人 | 结论 | 备注 |
|------|--------|------|------|
| 2026-09-04 | 用户 | 待检查 | 与 `20260904-mcp-gateway.md` 一并审批 |
| 2026-09-05 | 自主推进 | **已实现** | schema-mcp.sql 建表 + 11 条种子授权，`spring.sql.init` 幂等执行 |
