# MCP 网关核心设计（L5 工具协议层 · 双向网关 + 鉴权/审计/限流）

> 版本：v1.0 ｜ 状态：**待检查**（2026-09-04 提交用户审批，获批前禁止实现） ｜ 依据：《开发排期》W10（MCP 网关核心）、CLAUDE.md 七层铁律、`docs/design/architecture/20260901-tool-engine.md`（W5 已批准）、`20260901-w8-trip-scenario.md`（W8 已批准）、`docs/design/table/20260902-app-table.md`（W9 已批准）

---

## 1. 设计目的

**要解决的问题**：W5 起的本地工具（`ToolEngineService` + 内存 `ToolRegistry` + `AgentTool` SPI）是**进程内 SPI**——只有平台内部 Agent（`AgentRuntime` / `Workflow`）能经 `AppToolGate` 白名单调用，外部 Agent / 客户端无法用**标准协议**接入平台能力。排期 W10 要求：

1. 平台把 W5-W8 的本地工具以 **MCP（Model Context Protocol）标准协议**暴露出去（"商旅 6 工具全部经 MCP 调用"），让外部 Agent 可发现、可调用；
2. 网关具备**统一鉴权**（未授权租户调用被拒）与**全量审计**（谁、何时、调了什么、结果）与**限流**；
3. 为 W11 工具市场（消费**外部** MCP Server 工具）打下出站调用基础。

**不做会怎样**：平台工具能力仍封闭在进程内，无法被外部 Agent 生态消费；工具调用无租户级授权与审计留痕，P3 阶段闸门（≥10 工具经 MCP 上架、自助注册）与横切面"全链路 Trace"无法落地；W11 工具市场无从起步。

**范围**：本设计实现 **L5 MCP 网关 v1**——①入站：平台作为 MCP Server 暴露本地工具；②出站：平台作为 MCP Client 接入外部 MCP Server（骨架）；③统一鉴权中间件 + 租户级工具授权表 `t_tool_grant`；④工具调用审计与限流；⑤现有工具迁移策略（零重写适配器）；⑥WireMock 契约测试。**不做**：工具市场上下架流程（W11）、MCP stdio 传输（v1 仅 HTTP/SSE）、沙箱执行（W12）、多智能体（W13）。

---

## 2. 关键架构决策（ADR-20260904-01：双向网关模型）

### 2.1 平台同时承担 MCP Server 与 MCP Client 两种角色

```
                    ┌────────────────────────── 平台（L5 MCP 网关）──────────────────────────┐
                    │                                                                          │
 外部 Agent/客户端 ─┼─ Inbound  MCP Server ──► 鉴权Filter → 工具白名单 → ToolEngine → AgentTool  │
 (tools/list,      │      (spring-ai-mcp-server,  HTTP/SSE)                                    │
  tools/call)      │                                                                          │
                    │                                                                          │
 平台内 Agent ─────┼─ Outbound MCP Client ──► 远端 MCP Server（W11 工具市场接入外部工具）        │
 (AgentRuntime/     │      (spring-ai-mcp-client + ToolCallbackProvider)                       │
  Workflow)         │                                                                          │
                    └──────────────────────────────────────────────────────────────────────────┘
```

- **Inbound（入站，W10 重点）**：平台把 `ToolRegistry` 里的本地工具作为 MCP Server 端点暴露。任何 MCP Client 连入 → `tools/list` 发现工具 → `tools/call` 执行。执行仍走现有 `ToolEngineServiceImpl`（幂等键 / Schema 校验 / PAYMENT 403 / 超时全部复用，**不重写**）。
- **Outbound（出站，W10 骨架 / W11 完整）**：平台可配置接入外部 MCP Server，把远端工具包装成 `ToolCallback` 供内部 Agent 使用。W10 只做最小骨架 + WireMock 契约测试验证链路，W11 工具市场在其上扩展。
- **不做的方向**：只做单向（仅入站或仅出站）会让网关能力不完整、W11 又要重开架构。双向在 v1 一次定下，入站先行、出站留骨架。

### 2.2 迁移策略：适配器包装，零重写现有工具

现有 `AgentTool` SPI 携带独立元数据（name / description / parameters / permission / timeoutMs），与 Spring AI 的 `ToolCallback` / `@Tool` 是两套模型。**方案：不重写任何工具实现，写一个统一适配器**，把 `ToolEngineService`（或单个 `AgentTool`）包装为 MCP 可暴露的工具。

- 原因：W5-W8 已验证的幂等 / 校验 / 白名单 / 超时逻辑全部保留；只升级"协议暴露层"。
- 工具元数据 `ToolMeta` → 可直接映射 MCP `Tool` schema（name / description / inputSchema），无需转换成本。
- 迁移对照表（第 8 节）逐工具列出"本地调用 vs MCP 调用"差异，供回归核对。

---

## 3. 入站 MCP Server 设计（Inbound）

### 3.1 技术选型

| 项 | 选型 | 说明 |
|----|------|------|
| 依赖 | `spring-ai-mcp-server-webflux-spring-boot-starter` | 与项目 Spring AI 1.0.0 版本一致；WebFlux 承载 SSE 传输 |
| 传输 | HTTP/SSE | 跨进程远程调用标准形态；stdio 留 W11 若需本机工具再开 |
| 暴露路径 | `/{base-path}/mcp`（建议 `spring.ai.mcp.server.servlet.context-path` 前缀 `/mcp`） | 可配置，前缀避免与业务 REST 冲突 |
| Server 名 | `agent-platform` | `spring.ai.mcp.server.name` |

> ⚠️ 该 starter 依赖 WebFlux 传输，与项目当前 servlet 栈（`spring-boot-starter-web`）混用需确认是否引入冲突；**实现阶段先用一个独立 `@Configuration` 隔离，若 servlet+webflux 并存有问题则改用 servlet 版 starter**（见 9.1 备选）。此项为实现时首个验证点。

### 3.2 暴露哪些工具（租户级白名单）

MCP `tools/list` 是全局方法，但平台要求"未授权租户看不到/调不到未授权工具"。方案：

- **暴露全集给所有 client，调用时按租户授权拦截**（v1 简单）：`tools/call` 到达后，经 `t_tool_grant` 校验 `(tenant_id, tool)` 是否授权，未授权 → 返回错误。`tools/list` 保持全集（实现成本低）。
- **（演进）按租户过滤 `tools/list`**：用 `ToolCallbackProvider` / `McpServerCustomizer` 按 MDC 中 tenant 动态裁剪暴露清单。**v1 不做**（需自定义 server 工具发现钩子，实现复杂，收益有限），列演进项。

> 设计权衡：未授权租户"看得到但调不动"比"完全看不到"更简单且满足"调用被拒+审计"检查点；若安全要求更高再升级为清单过滤。

### 3.3 鉴权中间件（叠加在 MCP HTTP 端点）

入站 MCP 端点是普通 HTTP 端点，可叠加标准 Filter：

| 层级 | 机制 | 职责 |
|------|------|------|
| `McpAuthFilter`（`OncePerRequestFilter`，Order 高于业务） | 读 `X-Tenant-Id` + `X-Api-Key` | 解析并校验租户凭证（v1：租户 key 白名单，与 `AccessGateFilter` 的 tenant 解析对齐；校验失败 → 401/403） |
| MDC | 写 `tenant_id` | 与现有 `AccessGateFilter` 相同，下游审计 / `t_tool_grant` 自动带租户 |
| `McpRateLimitFilter` | 每租户 QPS 计数（`ConcurrentHashMap` 桶） | 限流；预留 Redis 版（P2 缓存启用后替换） |

> 凭证校验：v1 用租户级 API key（配置/`t_tenant` 预留），不做完整 OIDC（AccessGateFilter 注释已预留 SSO/OIDC 后续）。**不做**会怎样：MCP 端点裸奔，任何人可调写操作工具——违反"未授权调用被拒"检查点。

### 3.4 调用链路（tools/call 到工具执行）

```
MCP tools/call ─► McpAuthFilter(鉴权) ─► RateLimit ─► 适配器(AgentToolAdapter)
   │
   ├─ 解析 name + arguments → ToolEngineService.invoke(tenantId, appId=null, InvokeRequest(tool,args,idempotencyKey))
   │     ├─ AppToolGate：appId=null 时是否放行？→ 走租户级 t_tool_grant（见 5）而非 per-app 白名单
   │     ├─ PAYMENT → 403（复用）
   │     ├─ 写操作 → 幂等键强制（复用）
   │     └─ 超时 / 结果规整（复用）
   └─ 结果 → 映射为 MCP content[]（text 类型）+ isError 标记
```

**关键点**：MCP 调用没有 appId 概念（外部 Agent 不在 `t_app` 注册），授权判定从"per-app 白名单"切换为"**租户级授权表** `t_tool_grant`"。这是 `AppToolGate` 的补充而非替代——平台内 Agent 仍走 per-app，外部走 per-tenant。

### 3.5 幂等键在 MCP 层的强制

`tools/call` 对写工具（WRITE/PAYMENT）**强制幂等键**，与内部路径共用 `t_tool_invocation`（同一工具两条入口幂等语义一致）：

| 工具权限 | MCP 层要求 | 缺失时 |
|----------|-----------|--------|
| READ | 不要求（无副作用） | - |
| WRITE / PAYMENT | `arguments` 内或 `_meta.idempotencyKey` 必须携带 | 返回 JSON-RPC error（`-32602`，提示携带幂等键），不进入执行 |

幂等键由**调用方（外部 Agent）生成**，格式沿用平台约定 `{tenantId}:{gateway}:{uuid}`（≤128）。网关原样透传给 `ToolEngineService.invoke()`，其 `composeKey` 会再次以租户+appId 包裹防跨租户撞键；重复键 → 返回首次结果（幂等重放，`idempotentReplay=true`），与平台内路径行为一致。

### 3.6 Server 注册中心 / 健康检查 / 上下线事件（排期 W10 第一项 AI 任务）

平台作为 MCP Server，需登记自身能力并对外可探活（**单服务部署**，本设计收敛为进程内轻量实现，不做分布式注册）：

- **`McpServerRegistry`**（L5 `com.agent.tool.mcp`，进程内存 `ConcurrentHashMap`）：登记网关元数据——server 名 `agent-platform`、暴露端点路径、当前工具数、启动时间、最近一次 `tools/call` 心跳时间、健康状态（UP/DEGRADED）。
- **健康检查端点** `GET /api/mcp/health`：返回 `{ status, toolCount, uptimeMs, lastHeartbeatMs }`，供外部探活与运维看板。`toolCount=0` 时置 `DEGRADED`（工具未装配）。
- **上下线事件**：应用启动（`@PostConstruct`）与关闭（`@PreDestroy`）时各写一条审计日志（`phase=up / down`，带 server 名与工具数），不广播（单实例）；W11 南向接入外部 Server 时再升级为心跳轮询 + 上下线广播 + 故障剔除。

---

## 4. 出站 MCP Client 设计（Outbound，W10 骨架）

- 依赖：`spring-ai-mcp-client-spring-boot-starter`。
- 配置：`McpClient`（`HttpClientSseClientTransport`，指向外部 MCP Server URL）→ `SyncMcpToolCallbackProvider` → 注册为 `ToolCallbackProvider`，供内部 `ChatClient` / `AgentRuntime` 消费远端工具。
- v1 范围：提供一个可配置出站连接器骨架（`mcp.outbound.*` 配置：url / server-name / 启停），能连接 WireMock 模拟的假 MCP Server 并成功调用一个工具即达标；**不开放给真实业务**（真实接入 W11）。
- 审计：出站调用同样记录 `tool` / `target` / `latency` / `resultCode`（与入站共用 `ToolAuditService`）。

---

## 5. 租户级工具授权表 t_tool_grant

独立表结构文档：`docs/design/table/20260904-t-tool-grant.md`。要点：

- 列：`tenant_id` / `tool_name` / `permission` / `enabled`，UNIQUE `(tenant_id, tool_name)`。
- 授权语义：租户白名单工具。`tools/call` 到达 → 查表，未授权或 `enabled=false` → 403 + 审计记录。
- 与 `t_app.config_json.tools[]`（per-app 白名单）并存：平台内走 per-app；MCP 入站走 per-tenant。
- 种子：商旅 6 工具 + 客服常用工具预授权，保证 W10 检查点"商旅 6 工具经 MCP 调用"可跑通。

---

## 6. 工具调用审计（ToolAuditService）

复用 P1 审计模式（`AUDIT` logger → `logs/audit.log`，MDC 带 trace_id），字段与现有 `AuditService` 风格一致，但面向工具调用：

| 字段 | 来源 | 说明 |
|------|------|------|
| `trace_id` | MDC | 全链路 |
| `ts` | 日志自带 | |
| `tenant_id` | MDC（MCP 鉴权写入） | 谁 |
| `channel` | 入站/出站 | INBOUND_MCP / OUTBOUND_MCP / LOCAL（平台内 Agent 也补记） |
| `tool` | 入参 | 工具名 |
| `argsHash` | 入参 | 参数 SHA-256 前 8 字节（不回显明文，防敏感参数入日志） |
| `idempotencyKey` | 入参 | 写操作幂等键 |
| `resultCode` | 结果 | 0 / 4xx / 5xx |
| `latencyMs` | 结果 | 全链路耗时 |
| `denied` | 鉴权 | true=未授权被拒 |

> 架构铁律"所有对外调用必须记录 Token 用量"——MCP 工具调用本身无 LLM token；token 计量仍由 L6 拦截器负责，本条不冲突。审计与 `ToolInvocationRepository`（幂等/对账）职责分离：前者是横切观测，后者是执行语义。

---

## 7. 接口契约

### 7.1 入站 MCP 端点（符合 MCP 规范，非自定义 REST）

| 方法 | 路径 | 传输 | 说明 |
|------|------|------|------|
| `initialize` | `/mcp` (SSE) | SSE | 握手（MCP 规范） |
| `tools/list` | POST `/mcp` (message) | JSON-RPC | 返回 `Tool[]`（name/description/inputSchema），映射自 `ToolRegistry.listTools()` |
| `tools/call` | POST `/mcp` (message) | JSON-RPC | 入参 `{name, arguments}` → 执行 → `content[]`（text）+ `isError` |

请求/响应形状遵循 MCP 规范（2025-06-18 版本）：

```jsonc
// tools/call 请求
{ "jsonrpc": "2.0", "id": 1, "method": "tools/call",
  "params": { "name": "compare_flight", "arguments": { "city": "北京", "days": 3 } } }
// tools/call 响应
{ "jsonrpc": "2.0", "id": 1, "result": {
    "content": [ { "type": "text", "text": "{...}" } ], "isError": false } }
```

### 7.2 管理 API（平台自有 REST，统一 `Result<T>`）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/mcp/tools` | 入站暴露的工具清单（含 permission，供审计核对） |
| GET | `/api/mcp/grants?tenantId=` | 某租户工具授权列表（分页） |
| POST | `/api/mcp/grants` | 授权 / 撤销（写操作 → 幂等键） |
| GET | `/api/mcp/outbound` | 出站连接器状态（骨架） |

### 7.3 错误码映射（ToolEngine → MCP JSON-RPC error）

`tools/call` 执行失败时，网关把 `ToolEngineService` 的 `BizException` 错误码映射为 MCP JSON-RPC 错误结构 `{ jsonrpc, id, error: { code, message } }`：

| ToolEngine 错误码 | JSON-RPC error code | 说明 |
|-------------------|---------------------|------|
| 401 鉴权失败 | `-32001` | 未授权访问（租户凭证无效） |
| 403 授权拒绝 / PAYMENT | `-32003` | 工具未授权 / 支付级未放开 |
| 400 参数校验失败 | `-32602` | Invalid params，文案回给调用方可重试 |
| 404 工具未注册 | `-32602` | 工具不存在 |
| 409 幂等键冲突 | `-32009` | 同键此前失败，请换键重试 |
| 504 执行超时 | `-32004` | 工具执行超时 |
| 429 限流 | `-32029` | Too Many Requests |
| 500 其他 | `-32603` | 内部错误 |

### 7.4 契约测试（分层）

| 层 | 测试 | 方式 | 默认跑 |
|----|------|------|--------|
| 单元 | `McpGatewayTest` | 直接构造 JSON-RPC 请求，mock `ToolEngineService`，断言 tools/list 转换、tools/call 透传、鉴权/授权/限流/幂等键拦截、错误码映射 | ✅ |
| 契约 | `McpContractTest` | **WireMock 扮演 MCP Client** 向 `/mcp` 发 JSON-RPC 请求，验证协议响应符合 MCP 规范（tools/list 结构、tools/call content[]、错误结构） | ✅ |
| 端到端 | `McpE2eIT` | `spring-ai-mcp-client` 起真 client 连自家 server，验证 initialize→tools/list→tools/call（含一次写工具幂等重放断言） | `@Tag("integration")` 不默认跑 |

---

## 8. 现有工具迁移对照（W5-W8 本地工具 → MCP）

| 工具 | 权限 | 本地调用点 | MCP 化方式 | 差异 |
|------|------|-----------|-----------|------|
| policy_query | READ | Workflow n1 / Agent | 适配器包装 | 无（只读，天然幂等） |
| compare_flight | READ | Workflow n2a | 适配器包装 | 无 |
| compare_hotel | READ | Workflow n2b | 适配器包装 | 无 |
| book_order | WRITE | Workflow（HITL 后） | 适配器包装 | 需外部 client 携带 idempotencyKey |
| pay_order | PAYMENT | 引擎硬 403 | 适配器包装 | MCP 同样 403（未放开） |
| cancel_order / notify_user / policy_check | READ/WRITE | Workflow / Agent | 适配器包装 | 同上 |

**核心结论**：全部 8 个现有工具零重写，仅新增 `AgentToolAdapter` 把 `AgentTool`/`ToolEngineService` 桥接到 MCP。回归用 W8 回归套件（`scripts/eval/`）跑"MCP 链路 vs 本地链路"双跑，成功率不回退即达标。

---

## 9. 备选方案与放弃理由

| 方案 | 结论 | 理由 |
|------|------|------|
| 9.1 仅用 servlet 版 MCP starter（不用 webflux） | ⚠️ 实现时先验证 | 项目当前是 servlet 栈，servlet 版 starter 可能更顺；但 WebFlux 版是 Spring AI 1.0 MCP 的默认/主推形态，两者实现时二选一，以"能干净叠加 Filter 鉴权 + 不与现有 servlet 冲突"为准，此条在实现阶段定夺并回写本文档 |
| 9.2 只做入站，不做出站骨架 | ❌ | W11 工具市场要消费外部工具，届时重开架构成本高 |
| 9.3 工具迁移=用 @Tool 重写每个工具 | ❌ | 双份实现（原 AgentTool + @Tool），幂等/校验逻辑重复，违反"不重写已验证逻辑"；适配器桥接更稳 |
| 9.4 MCP 入站用 `tools/list` 全集 + 调用时授权（本文 3.2） | ✅ | 满足"未授权调用被拒+审计"检查点，实现成本最低；清单过滤列演进 |
| 9.5 自研 JSON-RPC 端点，不引 Spring AI MCP starter | ❌ | 协议细节易错、无生态支持；Spring AI 1.0 原生 MCP 是既选型 |

---

## 10. 影响分析（必查）

### 10.1 租户隔离
- `t_tool_grant` 行级 `tenant_id` 隔离；MCP 入站鉴权 Filter 强制解析租户并写入 MDC，下游授权/审计全带租户。**越权防护**：授权判定以 MDC 租户为准（来自请求头，篡改不提升权限，因为 key 校验绑定租户）。

### 10.2 数据血缘
- 工具调用审计（trace_id + tenant + tool + argsHash）可回溯到具体 MCP 请求；与 `ToolInvocationRepository`（幂等）同源可对账。

### 10.3 保留策略
- 审计日志沿用现有日志滚动（Logback）；`t_tool_grant` 为配置数据，随生命周期不删除。

### 10.4 对现有层的侵入
- `ToolEngineServiceImpl` / `AgentTool` / `AppToolGate` **零改动**；新增 `mcp` 子包（适配器 / Filter / Controller / Audit），依赖只向下。`ArchitectureTest` 预计零改动（新增包在 L5 内）。

---

## 11. 分步实施（获批后执行，与排期 W10 对齐）

| 步骤 | 内容 | 验收 |
|------|------|------|
| 1 | 加依赖 + 确认 servlet/webflux 共存 → 起 MCP Server 端点（空工具集） | `/mcp` 握手成功 |
| 2 | `AgentToolAdapter`：ToolRegistry → MCP 工具；`tools/list` / `tools/call` 打通现有引擎 | 商旅 6 工具 MCP 可调，回归双跑成功率不回退 |
| 3 | `McpAuthFilter` + `t_tool_grant` 授权表 + 建表 | 未授权租户调用被拒 + 审计落盘 |
| 4 | `ToolAuditService` + 限流 Filter | 每次 MCP 调用有审计记录 |
| 5 | 出站骨架 + WireMock 契约测试 | 假 MCP Server 调用成功；契约测试绿 |
| 6 | 管理 API + 首页入口 | 6 入口中工具网关可演示 |

---

## 12. 检查记录

| 日期 | 检查项 | 结果 |
|------|--------|------|
| 2026-09-04 | 用户计划审批（架构决策 / 表结构 / 接口契约 / 迁移策略） | 待检查 |

> 获批前，本设计所涉代码（依赖 / 适配器 / Filter / 授权表 / Controller）一律不实现。
