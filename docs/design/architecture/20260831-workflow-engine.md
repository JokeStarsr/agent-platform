# Workflow 引擎与 HITL 人工节点设计（L3 编排层）

> 版本：v1.0 ｜ 状态：**待检查**（2026-08-31 提交用户审批） ｜ 依据：《架构设计说明书》4.3（编排层/HITL）、《开发排期》W6、docs/design/architecture/20260831-agent-runtime.md（W4，已批准）、20260901-tool-engine.md（W5，已批准）

---

## 1. 设计目的

**要解决的问题**：W4 Agent Runtime 是"自由循环"（ReAct 决策驱动），适合客服这种探索型任务；但商旅场景是**固定流程**——查政策 → 比价 → 方案生成 → 人工确认 → 下单 → 通知，每一步顺序与准入条件都确定，且"人工确认"不可省略、可能挂起数天。平台目前没有固定流程执行引擎，P2 闸门"商旅成功率 ≥90%"与"写操作 100% 走 HITL"无法落地。

**不做会怎样**：商旅流程只能用 Agent Runtime 自由循环硬拼，无节点级变量、无条件分支、无并行、人工确认中断（服务重启）即流程丢失——与"崩溃恢复后能从断点继续"的周末检查点直接冲突。

**范围**：本设计实现 **Workflow 引擎 v1**——JSON 定义的 DAG 解析与执行引擎（五类节点：LLM/工具/条件分支/人工/并行聚合）、节点级状态持久化（每步落库、断点恢复、失败节点单独重试）、人工节点挂起-恢复-超时升级、补偿标记与回滚钩子、商旅主流程（Mock 工具版）。

**不做**：MCP 协议接入（P3）；Harness 代码沙箱（适配团 W6 后）；可视化流程编排 UI（P3）；流程多版本管理（v1 用 flow_def 快照，定义演进不追溯）；跨服务分布式流程（单服务部署，同 W4）；多智能体（W8）。

---

## 2. 核心模型

### 2.1 流程定义（JSON DAG，含嵌套与并行）

```json
{
  "flowId": "trip_booking",
  "name": "商旅主流程（Mock 版）",
  "timeoutMs": 1800000,
  "inputSchema": { "city": "string", "travelerId": "string", "days": "int" },
  "nodes": [
    { "id": "n1", "type": "TOOL", "tool": "policy_query",
      "args": { "city": "$.input.city" }, "out": "policy" },

    { "id": "n2", "type": "PARALLEL", "timeoutMs": 60000, "aggregate": "REQUIRE_ALL",
      "branches": [
        { "id": "n2a", "type": "TOOL", "tool": "compare_flight",
          "args": { "city": "$.input.city", "days": "$.input.days" }, "out": "flightOpts" },
        { "id": "n2b", "type": "TOOL", "tool": "compare_hotel",
          "args": { "city": "$.input.city", "days": "$.input.days" }, "out": "hotelOpts" }
      ] },

    { "id": "n3", "type": "LLM",
      "prompt": "出差政策：${n1.policy}\n比价结果：航班 ${n2a.flightOpts}，酒店 ${n2b.hotelOpts}\n生成出行方案（含预算内推荐与理由），输出 JSON。",
      "out": "plan" },

    { "id": "n4", "type": "HUMAN", "title": "方案确认",
      "content": "方案：${n3.plan}", "escalateAfterMs": 1800000 },

    { "id": "n5", "type": "TOOL", "tool": "book_order",
      "args": { "plan": "$.n3.plan" }, "out": "order", "rollbackTool": "cancel_order" },

    { "id": "n6", "type": "TOOL", "tool": "notify_user",
      "args": { "orderId": "$.n5.orderId", "travelerId": "$.input.travelerId" }, "out": "notify" }
  ],
  "edges": [ ["n1","n2"], ["n2","n3"], ["n3","n4"], ["n4","n5"], ["n5","n6"] ]
}
```

**定义约束**（引擎加载时校验，非法定义启动即失败）：
- 节点 id 全局唯一（含并行分支/子流程内），`[a-z0-9_]+`
- 图必须连通、无环（拓扑排序检测环 → 拒载）；所有节点（除 CONDITION 分支末端）必须可达且最终汇聚到终态（无出边的节点视为终态）
- 变量引用 `$.a.b` / `${n1.policy}` 在启动时校验引用路径存在（不存在的引用 → 定义非法）
- **嵌套**：`type=SUBFLOW` 节点引用另一段定义（`flowRef`），引擎递归展开执行，节点表内记录 `parent_node_id` 归属

**五类节点语义**：

| 类型 | 功能 | 入参 | 出参（写变量） | 失败语义 |
|------|------|------|----------------|----------|
| TOOL | 调 L5 ToolEngineService（write 工具引擎自动分配幂等键） | `args`（变量引用插值） | `out` 指向的变量 | 可重试（见 §2.5），重试复用幂等键 |
| LLM | 调 LlmGateway 生成（prompt 模板变量插值，JSON 模式） | `prompt` | `out` | 同 W4：连续失败 ≥3 次 FAILED |
| CONDITION | 按表达式选唯一出边 | `expr`（比较/存在性） | 无 | 表达式非法 → 定义校验失败 |
| HUMAN | 挂起等人工审批（§2.3） | `title/content/escalateAfterMs` | 审批结果写 `out` | REJECTED → 触发补偿，流程 FAILED |
| PARALLEL | 并行执行 `branches` + 聚合 | `branches/aggregate` | 各分支 out | §2.4 |

### 2.2 实例状态机

```
CREATED → RUNNING ──┬──→ COMPLETED            所有节点完成
  │         │  │    ├──→ FAILED               节点不可恢复失败 / 人工驳回（补偿已完成）
  │         │  │    └──→ CANCELED             用户主动取消（仅 RUNNING/WAITING_APPROVAL）
  │         └──→ WAITING_APPROVAL ─→ RUNNING  人工节点挂起 ↔ 审批通过恢复
  └──→ REJECTED      启动校验未通过（定义非法/无租户/输入校验失败）
```

HUMAN 节点挂起期间实例状态 `WAITING_APPROVAL`，其余节点状态 `RUNNING`。

### 2.3 人工节点：挂起-恢复-超时升级（商旅场景命脉）

**挂起**：节点执行到 HUMAN → 节点行写 `WAITING_APPROVAL` + `escalation_at = now + escalateAfterMs`（默认 30 分钟），实例置 `WAITING_APPROVAL`，发 `HUMAN_WAIT` 事件（SSE/审计）。**挂起状态 100% 落库**——不依赖内存 future（W4 用 CompletableFuture，重启即丢；W6 人工节点必须撑住"挂起 7 天后恢复"的周末检查点）。

**恢复（审批 API）**：`approve(instanceId, nodeRunId, approved, comment)`
- `approved=true`：节点 → COMPLETED，审批结果写变量，实例回 RUNNING 继续执行
- `approved=false`：节点 → FAILED(原因=REJECTED_HUMAN)，**触发补偿**（§2.6 逆序回滚已完成写节点），实例 → FAILED。v1 不支持驳回后修改输入重启同一流程（用户可重新 start 新实例）

**超时升级**：独立扫描器（@Scheduled 30s 间隔，查 `status=WAITING_APPROVAL AND escalation_at < now() AND escalated_at IS NULL`）→ 节点/实例置 `ESCALATED` 标记（仅标记，流程仍挂起等人工）、发 `HUMAN_ESCALATED` 事件、POST 流程定义配置的 `escalationUrl` 回调（带 instanceId/nodeId/escalationTime，幂等升级：escalated_at 已置则不再触发；回调失败下一轮扫描重试）。升级后人工仍可审批——超时升级只是**把"无人问津"变成"有人盯着"**，不自动改流程走向。

**服务重启**：每步落库 + 启动恢复：`WAITING_APPROVAL` 实例原样保留（升级扫描器自动接管超时判定）；`RUNNING` 实例从 `current_node_ids`（活动节点）继续执行，已 COMPLETED 节点不重跑。

### 2.4 并行聚合（PARALLEL）

- 分支作为独立节点行（`parent_node_id = 并行节点 id`）并行执行，出参分别写变量
- **聚合策略**：
  - `REQUIRE_ALL`（默认）：全部分支 COMPLETED 才继续；任一分支失败 → 整批已成功分支触发补偿回滚（写操作），流程 FAILED（不等待其余分支完成，先返回的失败即触发，其余分支结果作废）
  - `ALLOW_PARTIAL`：成功分支结果保留并继续，失败分支计入节点历史，事务上下文记录"部分成功"供下游 LLM 节点感知
- 每分支独立重试（§2.5），不因并行批内兄弟失败而阻塞重试机会

### 2.5 失败节点单独重试

节点 FAILED（非人工驳回、非不可重试工具错误）→ 实例停在该节点，`retryNode(instanceId, nodeRunId)` 单独重试：`attempt+1` 新节点行，TOOL 节点**复用原幂等键**（防重放扣减双倍），LLM 节点按新 attempt 重新生成。流程总超时 `timeoutMs` 仍生效（同 W4 优雅终止语义）。

不可重试错误（→ 直接流程 FAILED）：人工驳回、工具 403/404（权限/不存在）、Schema 校验失败。

### 2.6 补偿标记与回滚钩子

- **补偿清单**：`t_workflow_instance.compensation` 记录流程内已完成写工具节点 JSON 数组 `[{nodeId, tool, idempotencyKey, compensatedAt?}]`，节点每次成功提交写入
- **触发时机**：人工驳回（§2.3）、并行 REQUIRE_ALL 部分失败（§2.4）、用户取消时对已完成写节点回滚
- **回滚执行**：逆序逐个调用节点定义的 `rollbackTool`（如 `book_order` → `cancel_order`），**幂等键复用原键**——补偿动作与原动作同键，幂等层保证"补过一次不补第二次"；每个回滚完成后在补偿清单打 `compensatedAt` 标记；回滚失败 → 节点标记 `COMPENSATION_PENDING` 留审计，人工兜底（v1 不做自动重试升级，审计日志可见）

### 2.7 变量与断点恢复

- `t_workflow_instance.variables` 是唯一权威变量快照：每节点提交后原子整量更新（JSON 落库）；节点行同时存 `input/output_snapshot` 供调试与重试
- 恢复执行时以库中 variables + 各节点状态重建执行上下文，不依赖任何内存态

---

## 3. 接口契约（WorkflowService，L3 对外；Controller 在 access 层）

| 方法 | 说明 |
|------|------|
| `long start(String tenantId, String appId, WorkflowDef def, Map<String,Object> input)` | 启动流程，校验定义+输入，返回 instanceId（CREATED） |
| `Map<String,Object> detail(String tenantId, long instanceId)` | 实例详情（状态/变量/补偿清单/错误） |
| `List<Map<String,Object>> nodeHistory(String tenantId, long instanceId)` | 节点执行历史（含 attempt 与快照） |
| `void approve(String tenantId, long instanceId, long nodeRunId, boolean approved, String comment)` | 人工节点审批（§2.3） |
| `void cancel(String tenantId, long instanceId)` | 取消（仅 RUNNING/WAITING_APPROVAL，已成功写节点补偿回滚） |
| `void retryNode(String tenantId, long instanceId, long nodeRunId)` | 失败节点单独重试（§2.5） |
| `Flux<WorkflowEvent> stream(String tenantId, long instanceId)` | SSE 实时事件（复用 W4 sink 模式） |

错误经 GlobalExceptionHandler 统一包装；tenant 不匹配 → 403（同 W4 checkTenant）。

事件模型：`{instanceId, nodeRunId?, nodeId?, phase(NODE_START/NODE_DONE/HUMAN_WAIT/HUMAN_RESULT/HUMAN_ESCALATED/COMPENSATING/FLOW_END), status, ts, trace_id}`，落 `t_workflow_node_run` 或事件审计（v1 节点状态即事件历史，事件行不另建表，详情页由 nodeHistory 重建）。

---

## 4. 数据结构（建表；按 docs/templates/TABLE-DESIGN-TEMPLATE.md 治理）

### 4.1 `t_workflow_instance`（流程实例主表）

| 字段 | 类型 | 可空 | 说明 |
|------|------|------|------|
| `instance_id` | BIGSERIAL PK | NO | 实例号 |
| `tenant_id` | VARCHAR(64) | NO | 租户隔离（索引） |
| `app_id` | VARCHAR(64) | NO | 场景标识（TR_/CS_，同 W4） |
| `flow_id` | VARCHAR(64) | NO | 流程定义标识（如 trip_booking） |
| `flow_def` | TEXT | NO | 定义 JSON 快照（定义演进不追溯） |
| `status` | VARCHAR(24) | NO | CREATED/RUNNING/WAITING_APPROVAL/ESCALATED/COMPLETED/FAILED/CANCELED/REJECTED |
| `input` | TEXT | NO | 启动入参 JSON |
| `variables` | TEXT | NO | 变量快照 JSON（断点恢复权威源） |
| `compensation` | TEXT | NO DEFAULT '[]' | 补偿清单 JSON（§2.6） |
| `current_node_ids` | TEXT | NO DEFAULT '[]' | 活动节点 id JSON 数组（RUNNING 时恢复入口） |
| `trace_id` | VARCHAR(64) | YES | 全链路 Trace |
| `error_msg` | TEXT | YES | 终止原因（非 COMPLETED 时） |
| `created_at` / `started_at` / `finished_at` | TIMESTAMPTZ | - | 时间轴 |

**status=ESCALATED**：人工节点已超时升级但流程仍挂起（WAITING_APPROVAL 的人维视角）；v1 简化：实例状态保持 `WAITING_APPROVAL`，升级状态记在节点行 `escalated_at`，ESCALATED 不出现在实例状态枚举。

### 4.2 `t_workflow_node_run`（节点执行表）

| 字段 | 类型 | 可空 | 说明 |
|------|------|------|------|
| `node_run_id` | BIGSERIAL PK | NO | 执行行号（一次 attempt 一行） |
| `instance_id` | BIGINT FK | NO | 归属实例 |
| `node_id` | VARCHAR(64) | NO | 定义内节点 id |
| `node_type` | VARCHAR(16) | NO | LLM/TOOL/CONDITION/HUMAN/PARALLEL/SUBFLOW |
| `parent_node_id` | VARCHAR(64) | YES | 嵌套归属（并行分支/子流程，空=顶层） |
| `attempt` | INT | NO DEFAULT 1 | 重试计数（UNIQUE 联合键） |
| `status` | VARCHAR(24) | NO | PENDING/RUNNING/WAITING_APPROVAL/COMPLETED/FAILED/SKIPPED/CANCELED |
| `input_snapshot` | TEXT | YES | 本节点入参 JSON（入参求值后） |
| `output_snapshot` | TEXT | YES | 本节点出参 JSON（写 variables 前） |
| `idempotency_key` | VARCHAR(128) | YES | TOOL 写节点引擎分配的幂等键（重试/补偿复用） |
| `escalation_at` | TIMESTAMPTZ | YES | 人工节点超时点（WAITING_APPROVAL 时非空） |
| `escalated_at` | TIMESTAMPTZ | YES | 已升级时间（防重复升级+幂等回调） |
| `error_msg` | TEXT | YES | 失败原因 |
| `started_at` / `finished_at` | TIMESTAMPTZ | - | |

**索引（每个对应真实查询）**：

| 索引 | 字段 | 类型 | 支撑查询 |
|------|------|------|----------|
| idx_wf_inst_tenant | (tenant_id, created_at) | 普通 | 租户实例列表页 |
| idx_wf_inst_status | (status, created_at) | 普通 | 启动恢复扫描（RUNNING 实例） |
| idx_wf_node_inst | (instance_id) | 普通 | nodeHistory 按实例查 |
| idx_wf_node_pending | (status, escalation_at) | 普通 | 人工节点超时升级扫描器（30s 轮询） |

UNIQUE 约束：`(instance_id, node_id, attempt)`（重试 = 新 attempt 行，防并发重复执行）。逻辑外键（同 W4 惯例），不建物理外键。

**保留策略**：实例与节点 90 天归档（任务/变量快照含业务信息）；升级回调与审计依赖 trace_id ↔ instance_id 关联。

---

## 5. 备选方案与放弃理由

| 方案 | 结论 | 理由 |
|------|------|------|
| 自研 DAG 引擎（本文采用） | ✅ | 与 W4/W5 自研路线一致；核心 ~800 行可驾驭；满足"API 框架独立"铁律 |
| Flowable/Camunda（BPMN） | ❌ | 重型编排引擎，BPMN 建模/部署/租户改造成本高，单服务轻量部署不匹配；P3 若需可视化编排再评估 |
| Temporal/Zeebe（分布式工作流） | ❌ | 分布式基础设施，本机单服务阶段运维不可承受；与现有 JdbcTemplate 直落库模式冲突 |
| Spring State Machine | ❌ | 无 DAG/并行/嵌套/变量快照模型，状态机之外全要自研，等于没省 |
| 人工节点复用 W4 内存审批（CompletableFuture） | ❌ | 服务重启审批态即丢，直接违背"挂起 7 天后恢复/重启不丢"检查点；W6 必须 DB 持久化 + 扫描器 |

## 6. 对横切面的影响

- **租户隔离**：实例/节点行级 tenant_id；detail/approve/cancel/retryNode 全部强制校验 tenant 归属（403，同 W4）
- **血缘**：instance_id 挂 trace_id；TOOL 节点的 idempotency_key 与 t_tool_invocation 关联，可回溯"哪个流程哪一步调了哪个工具、是否真实执行/幂等重放"
- **成本治理**：LLM 节点按 W4 估算口径计 token（L6 计量拦截器落地后接真实值）；variables 快照含 token 统计位（v1 预留，不阻塞）
- **数据治理**：input/variables 快照可能含差旅个人信息 → PII 考量，90 天归档后脱敏；补偿清单与审计留存同一周期
- **审计**：AuditLogAspect 覆盖 WorkflowController；批量写操作（补偿回滚）必须带幂等键审计

## 7. 验收用例（排期点名三大场景 + 周末检查点）

| # | 场景 | 期望 |
|---|------|------|
| AC-1 | **挂起 7 天后恢复**：流程到 HUMAN 节点挂起；7 天后人工 approve | 期间服务可多次重启；approve 后流程从挂起处继续至 COMPLETED，已执行节点不重跑；超时扫描器不误升级（升级点未到） |
| AC-2 | **审批驳回回退**：HUMAN 驳回（已存在前置写节点） | 前置写节点按逆序被 rollbackTool 补偿，幂等键与原键一致（t_tool_invocation 断言不重复扣减），实例 FAILED，补偿清单全部打标记 |
| AC-3 | **并行部分失败**：PARALLEL 两分支一成功一失败（REQUIRE_ALL） | 整批中止，成功分支被补偿回滚，实例 FAILED；retryNode 单独重试失败分支成功后，流程可恢复继续（新 attempt 行，不重跑已成功分支） |
| AC-4 | **商旅主流程端到端（周末检查点）**：Mock 工具（policy_query/compare_flight/compare_hotel/book_order/cancel_order/notify_user）跑通 查政策→比价→方案→人工确认→下单→通知 | 中途 kill 服务重启 → 从断点继续，流程不丢；正常路径 COMPLETED；写操作均带幂等键 |

## 8. 实施计划（批准后）

1. `schema-workflow.sql`（幂等 DDL 挂 spring.sql.init）+ WorkflowRepository（instance/node_run 两层 CRUD + 补偿清单读写 + 扫描查询）
2. DAG 解析器：JSON → 图、拓扑排序（环检测）、定义校验、变量引用解析（`$.a.b`/`${n1.x}`）
3. 执行引擎：节点分派（TOOL 经 ToolEngineService/LLM 经 LlmGateway/CONDITION/HUMAN/PARALLEL/SUBFLOW）、变量提交、事件 emit（复用 W4 sink 模式）、断点恢复（启动扫描 RUNNING 实例）
4. 人工节点：挂起落库 + approve API + 超时升级扫描器（@Scheduled 30s）+ escalationUrl 幂等回调
5. 补偿：清单维护 + 逆序回滚执行（幂等键复用）+ COMPENSATION_PENDING 留痕
6. 商旅 Mock 工具 6 个（policy_query/compare_flight/compare_hotel/book_order/cancel_order/notify_user，注册进 W5 注册中心）+ trip_booking 定义 + 端到端走通
7. 测试：AC-1/2/3 单测（时间注入模拟超时与"7 天"）+ AC-4 手动端到端验收

---

## 9. 变更历史

| 版本 | 日期 | 变更 | 说明 |
|------|------|------|------|
| v1.0 | 2026-08-31 | 初版 | 交用户审批（铁律：批准前不写实现） |