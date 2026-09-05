# t_tool_catalog 工具目录表设计

> 版本：v1.0 ｜ 状态：**已批准**（2026-09-05 用户审批通过） ｜ 依据：《开发排期》W11（工具市场与自助上架）、架构设计 `docs/design/architecture/20260905-tool-marketplace.md`（W11，已批准）、CLAUDE.md 设计文档铁律

---

## 1. 基本信息

| 项 | 值 |
|----|----|
| 表名 | t_tool_catalog |
| 所属数据域 | 工具（L5） |
| 所属架构层 | L7 数据层（`com.agent.data.toolmarket`） |
| 目标数据库 | PostgreSQL（同项目现有表，`spring.sql.init.mode=always` 幂等 DDL 启动执行） |
| 作者 / 日期 | 2026-09-05 |
| 状态 | 待检查 |

---

## 2. 设计目的

**要解决的问题**：W10 前工具是"代码里 @Component 注册"（ToolRegistry 内存表），无目录、无版本、无上下架、无自助注册。W11 工具市场要求：检索、分类、版本管理、上下架流程、自助注册（注册到可调用 ≤ 30 分钟）、调用统计。

**没有它会怎样**：工具生态封闭在开发者代码里；新工具接入仍要改代码（P3 闸门"自助注册可用"无法达成）；工具版本无法回溯（调用时用的哪版定义说不清）；下架/上线需重启。

**设计原因**：版本与状态须在线管理、重启不丢、管理 API 分页直查 → 落 DB（与 `t_app` 先例一致）；内置工具（W5-W8 现有 11 个）作为种子在启动时同步进目录（`source=BUILTIN`），保证存量工具立即可见可管理。

---

## 3. 字段定义（逐项附说明）

| 字段 | 类型 | 可空 | 默认值 | 说明 |
|------|------|------|--------|------|
| id | BIGSERIAL | NO | 自增 | 主键 |
| tool_name | VARCHAR(64) | NO | - | 工具注册名（执行用，`[a-z_][a-z0-9_]*`）；同一工具不同版本共享此名 |
| version | INT | NO | 1 | 版本号；发布新版 = 新行 version+1，旧版本只读保留 |
| display_name | VARCHAR(128) | NO | - | 目录展示名（中文，如"查询订单状态"） |
| description | VARCHAR(512) | NO | - | 用途说明（模型 Prompt 用，≥20 字符校验） |
| category | VARCHAR(32) | NO | 'utility' | 分类枚举：business/communication/data/schedule/payment/utility |
| parameters | JSONB | NO | - | 参数 JSON Schema（ToolEngine 校验用，与 ToolMeta.parameters 同构） |
| permission | VARCHAR(16) | NO | 'READ' | READ / WRITE / PAYMENT（PAYMENT 仅登记，引擎硬 403） |
| source | VARCHAR(24) | NO | 'SELF_REGISTERED' | 来源：BUILTIN(内置)/SELF_REGISTERED(自助注册)/OUTBOUND_MCP(外部MCP) |
| external_url | VARCHAR(256) | YES | NULL | source=OUTBOUND_MCP 时的远端 Server URL；其余为 NULL |
| external_tool_name | VARCHAR(64) | YES | NULL | 远端原始工具名（出站前缀化前的原名） |
| status | VARCHAR(16) | NO | 'DRAFT' | DRAFT(草稿)/PUBLISHED(已发布，可执行)/OFF_SHELF(已下架) |
| enabled | BOOLEAN | NO | TRUE | 执行掩码开关（PUBLISHED 且 enabled=true 才进执行路径；软停用备用） |
| owner_id | VARCHAR(64) | NO | - | 注册人（自助注册者 / builtin 同步标记 'platform'）；审计与运营归属 |
| testcase_json | JSONB | NO | '[]' | 测试用例数组 `[{name, arguments, expectCode}]`；发布时 smoke 执行 |
| created_at / updated_at | TIMESTAMPTZ | NO | now() | 时间戳（上下架/版本操作也更新 updated_at） |

> 说明：**为什么不用独立版本表**——工具定义行级量小（数十~数百行），版本=新行天然保留历史；拆成 version 子表查询复杂、收益低，待工具超过 500 版本再拆。
> 说明：**enabled 与 status 并存**——status 是目录语义（草稿/发布/下架），enabled 是执行执行的快速掩码位（查 `WHERE status='PUBLISHED' AND enabled=true` 单索引覆盖热路径）。

---

## 4. 索引设计

| 索引名 | 字段 | 类型 | 支撑查询场景 |
|--------|------|------|-------------|
| uq_tool_catalog_name_version | (tool_name, version) | UNIQUE | 目录详情：`WHERE tool_name=? ORDER BY version DESC`（当前版本取第一条） |
| idx_tool_catalog_status | (status, enabled) | 普通 | 执行解析热路径：`WHERE status='PUBLISHED' AND enabled=true`（注入 ToolEngine 掩码） |
| idx_tool_catalog_category | (category, status) | 普通 | 目录页分类筛选：`WHERE category=? AND status=?` |

> 热路径是"发布且启用"集合的扫描（内置 + 自注册总量 < 100），普通索引即可；唯一索引防同名同版本撞键。

---

## 5. 关联关系与约束

- **逻辑外键**：`tool_name` → `ToolRegistry`/`t_tool_grant.tool_name`（执行时解析，不建物理外键，同项目惯例）。
- 与 `t_tool_grant` 关系：目录控制"工具是否存在/可执行"；授权表控制"某租户能否调用"。**独立判定**：不在目录的（OFF_SHELF/DRAFT）→ 执行自然 404；在目录但未授权 → 403。
- 与 `t_app.config_json.tools[]` 关系：per-app 白名单引用工具名；目录下架后，白名单引用自然失效（执行 404）。

---

## 6. 租户隔离设计 ★必查项

| 项 | 内容 |
|----|------|
| 隔离方式 | **平台级共享目录**（工具是平台资产，非租户资产） |
| 隔离字段 | 无 tenant_id 行维度；`owner_id` 记录操作人（审计归属） |
| 越权防护 | ①目录对所有租户只读可见（列表/详情）；②**调用授权由 t_tool_grant 独立管控**（per-tenant 授权，W10 已实现）——租户 A 即使看到目录也不能调未授权工具（授权判定在 ToolEngine 入口，与 MCP 一致）；③注册/发布/下架等写操作校验 `operator_id` 权限（v1 统一 admin 角色可写，多租户 RBAC 演进项） |

> 关键点：目录是"读共享写受限"——公开目录的可见性 ≠ 调用权限；授权红线仍然由 t_tool_grant 行使，本表不承担授权职责。

---

## 7. 数据治理影响 ★必查项

| 项 | 结论 |
|----|------|
| 是否含 PII | 否（工具元数据 + 测试用例参数；若测试用例含假用户数据，脱敏要求与 RAG 测试文档一致） |
| 数据血缘 | `owner_id` 可回溯注册人；`source` 可回溯来源（BUILTIN/SELF_REGISTERED/OUTBOUND_MCP）；调用时的工具版本可回溯（audit.log 的 tool + 时间戳 ↔ version） |
| 保留策略 | 配置数据，随生命周期不删除；下架=DRAFT→OFF_SHELF 保留历史版本；物理删除仅误建清理 |
| 删除语义 | 下架 = status 置 OFF_SHELF（保留行，执行掩码移除）；物理删除仅用于误建且未被引用 |

---

## 8. 建表 SQL（设计批准后才执行）

```sql
-- 批准状态：待检查（获批后由用户确认执行）
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
```

---

## 9. 演进预判

- **量级**：内置 11 + 自助注册数十 → 一年内数百行，无膨胀压力。
- **最先撑不住的点**：版本行膨胀（频繁发版的工具产生多行）+ 单行 JSONB 变大（testcase 增多）。预判阈值：单工具 > 50 版本或目录 > 5000 行时，将"当前版本+历史版本"拆为双表（t_tool / t_tool_version）。
- 出现按角色/团队细分工具可见性 → 引入目录级授权（grant 模板，`t_tool_grant` 演进，W10 表文档 §9 已预判）。

---

## 10. 检查记录

| 日期 | 检查人 | 结论 | 备注 |
|------|--------|------|------|
| 2026-09-05 | 用户 | 待检查 | 与 `20260905-tool-marketplace.md` 一并审批 |