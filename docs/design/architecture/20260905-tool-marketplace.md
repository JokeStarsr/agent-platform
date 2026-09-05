# 工具市场与自助上架设计（L5 工具协议层 · W11）

> 版本：v1.0 ｜ 状态：**待检查**（2026-09-05 提交用户审批，获批前禁止实现） ｜ 依据：《开发排期》W11（工具市场与自助上架）、`docs/design/architecture/20260904-mcp-gateway.md`（W10 已批准）、`20260901-tool-engine.md`（W5 已批准）、CLAUDE.md 七层铁律与设计文档铁律

---

## 1. 设计目的

**要解决的问题**：W10 MCP 网关已把本地工具以标准协议暴露（入站）并留下出站骨架，但工具本身仍停留在"代码注册"层面——没有目录、没有搜索结果、没有版本管理、没有自助上架流程。排期 W11 要求：

1. **工具目录**：检索、分类、版本管理、上下架流程（工具不再是"代码里 @Component 一注册就完事"，而是可管理、可运营的资产）；
2. **自助注册 API**：新工具注册到可调用 ≤ 30 分钟（G3 实证），注册时校验 Schema 合法性、测试用例齐全性；
3. **补完 W10 出站 MCP Client**：真实连接外部 MCP Server（`McpOutboundConnector` 目前是 SKELETON 态）；
4. **工具选择准确率评测**：50 条任务 → 期望工具的评测集，接入 CI；
5. **调用统计与错误率看板**：工具调用量、错误率可视化。

**不做会怎样**：工具生态封闭在"开发者写代码"，业务方无法自助接入；工具质量无门禁（随意注册一个 schema 错的工具 = 模型选择准确率崩盘）；外部 MCP Server 工具无法被平台内 Agent 消费（W13 多智能体的工具需求无法满足）；P3 闸门"≥10 工具经 MCP 上架、自助注册可用"无法达成。

**范围**：本设计实现 **L5 工具市场 v1**——①工具目录表 `t_tool_catalog`（版本/分类/上下架）；②自助注册 API + 校验器；③出站 MCP Client 真实连接；④工具调用统计看板；⑤工具选择准确率评测集 + CI 门禁。**不做**：执行沙箱（W12）、Skill Hub（W14）、多智能体（W13）。

---

## 2. 关键架构决策（ADR-20260905-01：目录即注册表）

### 2.1 工具目录是"DB 为权威"的注册表，代码注册降级为"内置种子"

```
┌────────────────────────── 平台（L5 工具市场） ──────────────────────────┐
│                                                                        │
│  内置工具(W5-W8) ──► ToolRegistry(内存) ──┐                             │
│  自助注册    ──► t_tool_catalog (DB权威) ──┼──► ToolEngine → AgentTool   │
│  外部 MCP    ──► McpOutboundConnector ───┘                             │
│     (W11 真实连接，包装为 ToolCallback)                                 │
└────────────────────────────────────────────────────────────────────────┘
```

- **内置工具**（policy_query / compare_flight / book_order 等 W5-W8 的 11 个）：启动时从 `ToolContextInitializer` 扫描 `AgentTool` beans 同步到 `t_tool_catalog`（`source=BUILTIN`，`version=1`，`status=PUBLISHED`），保证闸门"商旅 6 工具经 MCP 上架"可跑通。
- **自助注册**：POST `/api/tool-market/register` → 校验器（Schema 合法性 / 描述非空 / 测试用例齐全）→ 落库 `status=DRAFT` → 预览 / 发布 → `PUBLISHED`（不经过 MCP，直接进 ToolEngine 执行，与 MCP 网关共用 `t_tool_grant` 授权）。
- **出站工具**：外部 MCP Server 连接成功后，`McpOutboundConnector` 拉取远端工具列表，包装为 `ToolCallback` + 同步登记到目录（`source=OUTBOUND_MCP`，`external=true`），供平台内 Agent 消费。
- **为何目录存 DB**：版本管理（行级历史）、上下架状态在线修改重启不丢（与 t_app 先例一致）；代码注册（ToolRegistry）保留为"执行时解析"的快速路径，目录负责"管理面"。

### 2.2 工具目录与 MCP 网关的关系（不重做 W10）

- 入站 MCP Server（W10 已实现）：`tools/list` 继续从 `ToolRegistry` 暴露**已发布**工具（`ToolRegistry` 由目录驱动：`PUBLISHED` 才进注册表掩码，`DRAFT`/`OFF_SHELF` 不进执行）。
- 授权继续走 `t_tool_grant`（W10 已实现，零改动）。
- 出站（W11 补齐）：`McpOutboundConnector` 从 SKELETON → 真实连接，远端工具进入目录供内部 Agent 用。
- **不做的方向**：不新建一套"目录专用"的 auth/执行链路，复用 ToolEngine + t_tool_grant + 审计。

---

## 3. 工具目录数据模型

独立表结构文档：`docs/design/table/20260905-t-tool-catalog.md`。要点：

| 项 | 决策 |
|----|------|
| 表名 | `t_tool_catalog`（L7 `com.agent.data.toolmarket`） |
| 核心列 | `tool_name` / `display_name` / `description` / `category` / `parameters`(JSONB) / `permission` / `version` / `source`(BUILTIN/SELF_REGISTERED/OUTBOUND_MCP) / `external_url` / `status`(DRAFT/PUBLISHED/OFF_SHELF) / `owner_id` / `testcase_json` |
| 版本化 | UNIQUE `(tool_name, version)`；发布新版本 = 新行（version+1），旧版本保留历史 |
| 删除语义 | 下架 = `OFF_SHELF`；物理删除仅误建清理（与 t_tool_grant 软撤销先例一致） |

**category 分类**（v1 枚举，用于目录页筛选）：
`business`(商旅/客服) / `communication`(通知/邮件) / `data`(查询/报表) / `schedule`(日历/提醒) / `payment`(支付,恒 403 登记) / `utility`(通用工具)

---

## 4. 自助注册 API（§7.2 接口契约展开）

### 4.1 注册校验器（`ToolRegistrationValidator`，L5）

| 校验项 | 规则 | 失败时 |
|--------|------|--------|
| 名称 | 匹配 `[a-z_][a-z0-9_]*`，全局唯一（目录内唯一；与已发布工具冲突 → 409） | 400 + 具体原因 |
| 描述 | 非空，≥ 20 字符（太短模型无法区分工具） | 400 |
| parameters | 合法 JSON Schema（用工具引擎已有的 everit json-schema 校验器验证结构） | 400 + schema 错误位置 |
| permission | 枚举 READ/WRITE/PAYMENT；PAYMENT 仅登记不放开（引擎仍硬 403） | 400 |
| 测试用例 | ≥ 1 条 `{name, arguments, expectCode}`（发布时执行 smoke 校验） | 400 |

### 4.2 注册→发布→可用的生命周期（目标 ≤ 30 分钟）

```
POST /api/tool-market/register → 校验 → status=DRAFT（记录 tests）
POST /api/tool-market/preview → 管理端可试调（走 ToolEngine smoke 模式，不落库）
POST /api/tool-market/{id}/publish → 执行 testcase smoke（全绿才 PUBLISHED）→ 注入 ToolEngine
  → 自动（可选）grant 到调用方租户（t_tool_grant）→ 即可被 Agent/MCP 消费
```

**P3 闸门"自助注册到可调用 ≤ 30 分钟"**：v1 的 30 分钟指"操作时间"（填表 + 校验 + 测试 + 发布），因全流程 API 化，实测应远小于 30 分钟。

---

## 5. 出站 MCP Client 真实连接（W10 骨架收尾）

### 5.1 连接器升级（`McpOutboundConnector` SKELETON → REAL）

```
McpOutboundProperties(agent-platform.mcp.outbound.targets[])
   └─ 每个 enabled target：
        HttpClientSseClientTransport(url) ─► McpClient.sync(transport) ─► client.initialize()
              ─► client.listTools() ─► SyncMcpToolCallbackProvider(client)
              ─► getToolCallbacks() 注册为 ToolCallbackProvider（供 ChatClient/AgentRuntime 消费）
              ─► 同步登记 tools 到 t_tool_catalog（source=OUTBOUND_MCP, external_url）
```

- 连接建立在 `@PostConstruct`（应用启动时）；失败 → status=ERROR 记审计，下次重试（定时 5 分钟 + 手动触发 API）。
- 工具命名前缀：远端工具名 `foo` 默认加前缀 `{targetname}_foo`（避免与本地工具重名且可追溯来源），前缀策略可配置。
- 调用链路：内部 Agent → ToolCallback → `client.callTool()` → 远端执行 → 结果回传；审计走 `McpAuditService`（channel=OUTBOUND_MCP）。

### 5.2 出站契约验证（McpE2eIT，@Tag("integration") 不默认跑）

- WireMock 扮演外部 MCP Server（SSE 端点 `/mcp/sse` + message 端点），起真实 `McpClient` 连上 → 验证 `initialize → tools/list → tools/call` 全链路。
- 本测试补上 W10 §7.4 "端到端 integration" 的缺口。

---

## 6. 调用统计与错误率看板

### 6.1 数据来源（不新建打点表）

| 维度 | 来源 | 说明 |
|------|------|------|
| 调用量 / 错误率 / 时延 | `logs/audit.log`（McpAuditService 已记录 result_code / latency_ms / tool） | 读日志文件流或引入日志聚合（v1 直接扫目录） |
| 幂等特点 / 重放 | `t_tool_invocation`（W5 已有表） | status / result_payload |
| 工具目录状态 | `t_tool_catalog` | 上下架统计 |

> 备选：新建 `t_tool_invocation_stat` 统计表（定时聚合）。v1 **不建**——数据量级小（个位数租户 × 每日数百调用），直接扫 audit.log 或 count t_tool_invocation 足够；统计需求确定后再引入聚合表（演进项）。

### 6.2 看板 API（§7.2）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/tool-market/stats?days=7&tenantId=` | 按工具聚合：总调用/成功/失败/平均时延/P95，7 天窗口 |

---

## 7. 接口契约

### 7.1 目录与上架 API（管理面，统一 Result<T>）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/tool-market` | 目录列表（分页 + category/status/搜索过滤） |
| GET | `/api/tool-market/{toolName}` | 工具详情（含当前版本 + 历史版本列表） |
| POST | `/api/tool-market/register` | 自助注册（body: ToolRegRequest）→ 返回 id + DRAFT |
| GET | `/api/tool-market/{id}/testcases` | 查看工具测试用例 |
| POST | `/api/tool-market/{id}/preview` | DRAFT 试调（body: arguments）→ 不落库存调用记录 |
| POST | `/api/tool-market/{id}/publish` | 发布（跑测试用例 smoke，全绿 → PUBLISHED） |
| POST | `/api/tool-market/{id}/off-shelf` | 下架（→ OFF_SHELF，从 ToolEngine 掩码移除） |
| POST | `/api/tool-market/{id}/new-version` | 发新版本（body: 新版 ToolRegRequest，version+1） |
| GET | `/api/tool-market/stats` | 调用统计（第 6 节） |
| GET | `/api/tool-market/outbound` | 出站连接器状态（复用 /api/mcp/outbound，此处别名展示） |

**ToolRegRequest body**：
```jsonc
{
  "toolName": "query_order_status",
  "displayName": "查订单状态",
  "description": "按订单号查询订单当前状态（客服场景）…（≥20字）",
  "category": "business",
  "parameters": { "type": "object", "properties": { "orderId": { "type": "string" } }, "required": ["orderId"] },
  "permission": "READ",
  "testcases": [ { "name": "正常查询", "arguments": { "orderId": "ORD-001" }, "expectCode": 0 } ]
}
```

### 7.2 工具选择准确率评测集（scripts/eval/tool-selection.json）

50 条任务 → 期望工具，进 CI（复用 golden-set-gate.yml 结构）：

```jsonc
// 每条
{ "task": "帮我把下周二下午3点的会议安排到日程里",
  "expectedTool": "calendar_create_event",
  "falseTools": ["notify_user", "send_coupon"] }  // 干扰项（可选）
```

评测方式：构造系统 Prompt（含全部"已发布"工具描述）→ 模型返回 tool 调用 → 判断选的工具 == expectedTool。**50 条平均准确率 ≥ 90%**（P3 闸门）→ 接入 CI 门禁（失败阻断合并）。

---

## 8. 10 个目标工具清单（W11 交付物）

| # | 工具 | 分类 | 权限 | 来源 | 说明 |
|---|------|------|------|------|------|
| 1-8 | 现有 8 个（policy_query/compare_flight/compare_hotel/policy_check/book_order/pay_order/cancel_order/notify_user） | business | 混合 | BUILTIN | W5-W8 已有，登记进目录 |
| 9-11 | 客服 3 个（query_recent_orders/send_coupon/refund_order_partial） | business | 混合 | BUILTIN | 已有，一并登记 |
| 12 | `calendar_create_event` | schedule | WRITE | 示例自注册 | 演示"自助注册到可调用"流程 |
| 13 | `report_daily_summary` | data | READ | 示例自注册 | 演示 stats 看板数据 |
| 14 | `email_notify` | communication | WRITE | 示例自注册 | 演示 OUTBOUND_MCP 或自注册 |

> 实际以"能演示 3 个注册→发布→可调用闭环 + 稳定 ≥10 已发布"为准，不硬凑数量；出站目标定向到 WireMock 假 Server（本地起一个演示用外部工具源）。

---

## 9. 备选方案与取舍

| 方案 | 结论 | 理由 |
|------|------|------|
| 9.1 目录存内存不落 DB（ToolRegistry 直接扩展字段） | ❌ | 版本历史、上下架状态、owner、testcase 都是配置资产，DB 持续化与 t_app 先例一致；内存无法在线管理 |
| 9.2 自研统计表实时聚合调用量 | ❌ | 数据量小，扫 audit.log / count t_tool_invocation 已够；统计粒度需求确定后再建聚合表 |
| 9.3 自助注册直接进执行（不设 DRAFT/发布门禁） | ❌ | 无 testcase smoke 的"注册"无法保证 Schema 可执行，等于 P3 门禁失效；DRAFT→publish 是质量控制点 |
| 9.4 出站工具不登记目录、只包装 ToolCallback | ❌ | 目录是统一管理面（审计/看板/权限都要它）；不登记则出站工具不可见、不可治理 |
| 9.5 工具选择评测用真实 Agent 跑 | ❌ | 成本高、慢；v1 用"工具描述→期望工具"的单步选择（同 W5 思路），粒度够判定描述质量 |

---

## 10. 影响分析（必查）

### 10.1 租户隔离
- `t_tool_catalog` 是**平台级**目录（工具是平台资产，非租户资产）——`tenant_id` 不是行主维度，但上下架/注册操作记录 `operator_id`；工具可见性：所有租户可见目录，**调用权限由 t_tool_grant 管控**（W10 已实现，per-tenant 授权不变）。

### 10.2 数据血缘
- 调用审计（McpAuditService trace_id + tool + argsHash）回溯到具体调用；目录 version 字段可回溯"调用时用的哪版工具定义"→ 用 version 与 audit 时间戳关联。

### 10.3 保留策略
- 目录为配置数据，随生命周期不删；下架保留（OFF_SHELF）；物理删除仅误建；outbound 连接状态自动修复（重连）。

### 10.4 对现有层侵入
- `ToolEngineService` / `AgentTool` / `McpAuthFilter` / `t_tool_grant` **零改动**；新增 `toolmarket` 子包（注册器/校验器/Controller）+ `McpOutboundConnector` 从 SKELETON 升级到 REAL（只改实现方法体，不改接口）；`ArchitectureTest` 预计零改动（新增包在 L5/L7 内）。

---

## 11. 分步实施（获批后执行）

| 步骤 | 内容 | 验收 |
|------|------|------|
| 1 | `t_tool_catalog` 建表 + ToolCatalogRepository（L7）+ 启动时内置工具同步（BUILTIN 种子） | 目录 API 返回 11 个内置工具 |
| 2 | 目录管理 API：列表/详情/分页/过滤 + 管理页 | `/tool-market/` 页面可用 |
| 3 | 自助注册器 + 校验器 + DRAFT/preview/publish/off-shelf/new-version | 注册 → 发布 → 可调用闭环 ≤ 30 分钟 |
| 4 | 出站 MCP 真实连接（McpOutboundConnector REAL）+ WireMock 假 Server 演示 | WireMock SSE 连接成功，远端工具可用 |
| 5 | 调用统计 API + 看板 | stats 返回真实调用/错误率/时延 |
| 6 | 工具选择准确率评测集（50 条）+ CI 门禁 | 50 条 ≥ 90%（P3 闸门）接入 CI |

---

## 12. 检查记录

| 日期 | 检查项 | 结果 |
|------|--------|------|
| 2026-09-05 | 用户计划审批（架构决策 / 表结构 / 接口契约 / 迁移策略） | 待检查 |

> 获批前，本设计所涉代码一律不实现（CLAUDE.md 设计文档铁律）。