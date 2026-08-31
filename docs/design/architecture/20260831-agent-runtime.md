# Agent Runtime 规格设计（L3 编排层）

> 版本：v1.1 ｜ 状态：**已批准**（2026-08-31 用户审批通过，实现完成） ｜ 依据：《架构设计说明书》4.3.1（ReAct 循环 + 护栏参数表）、《开发排期》W4、CLAUDE.md 七层铁律

---

## 1. 设计目的

**要解决的问题**：P2 阶段起平台进入编排时代——客服场景要迁移到编排层（P2 闸门"迁移后指标不回退"）、商旅 Agent 需要带护栏的多步执行。目前平台没有任何 Agent 执行引擎，单轮 RAG 检索（L4）无法承载"多步推理 + 工具调用 + 人工确认"任务。

**不做会怎样**：客服只能保持单轮 RAG 形态，无法接入搜索/查单/写操作类工具；商旅（W6 Workflow 前置）无从落地；P2 两个闸门（商旅成功率 ≥90%、写操作 100% 走 HITL）全部无法建立。

**范围**：本设计只做 **Agent Runtime v1**（ReAct 循环 + 四重护栏 + trace 留痕 + 回放 API）。不做：Workflow 引擎（W6）、多智能体（W8）、Skill Hub（W9）、Harness 代码沙箱（W5 起，v1 仅接函数式工具）。工具协议层（L5 MCP）尚未实现，v1 用 Spring AI ToolCallbacks 原生工具，W5 工具注册中心落地后兼容迁移。

---

## 2. 核心模型

### 2.1 循环状态机

```
CREATED → RUNNING ──┬─→ COMPLETED     任务完成，产出最终答案
  │         │  │    ├─→ TERMINATED    maxSteps / loopDetector 触发（优雅终止）
  │         │  │    ├─→ BUDGET_EXHAUSTED  tokenBudget 耗尽（优雅终止）
  │         │  │    ├─→ TIMEOUT        超过 timeout 硬上限（任务可重放）
  │         │  │    └─→ FAILED         未捕获异常（含 LLM 连续失败 ≥3 次）
  │         └──┤              └─→ CANCELED    用户/系统主动取消
  └──→ REJECTED   任务校验未通过（无租户/空任务/超出单次并发配额）
```

RUNNING 内部循环（每步一次 LLM 调用 + 至多一次工具执行）：

```
PLAN（LLM 思考）→ [写操作? → HITL 挂起等待审批] → ACT（工具执行）→ OBSERVE（结果写回）→ REFLECT（LLM 反思）→ 下一轮 PLAN
```

- **输入契约**（每轮传给 LLM）：system（角色+护栏约束）+ 任务目标 + 累积历史（已执行步骤的动作/结果摘要）+ 可用工具清单
- **输出契约**（LLM 返回，JSON 模式强约束）：`{thought, action: {type: REASON|TOOL_CALL|FINAL_ANSWER, tool?, args?, answer?}}`
  - `type=REASON`：纯思考，不调工具，直接进入下一轮（计步）
  - `type=TOOL_CALL`：执行指定工具，参数经 Schema 校验，非法参数带错误重试（上限 2 次，见 W5 细化）
  - `type=FINAL_ANSWER`：携带最终答案，进入 COMPLETED
- **循环检测**：FINAL_ANSWER 之前，相同动作签名（工具名+参数规范化哈希）连续出现 3 次 → 触发 loopDetector。

### 2.2 护栏参数表（默认值来自架构说明书 4.3.1）

| 参数 | 客服场景默认 | 商旅场景默认 | 触发后行为 |
|------|-------------|-------------|-----------|
| `maxSteps` | 10 | 25 | TERMINATED，优雅终止 |
| `tokenBudget` | 32K/请求 | 60K/请求 | BUDGET_EXHAUSTED，整理已得结果输出 |
| `timeout` | 60s | 180s | TIMEOUT，任务标记可重放 |
| `loopDetectorThreshold` | 连续 3 次相似动作 | 同左 | 强制 REFLECT 一轮，再犯则 TERMINATED |
| `writeOpsPolicy` | 白名单 + 确认 | 同左 | 写操作 HITL 挂起，预览待审批 |
| `maxConcurrency` | 单租户 5 并发 | 单租户 10 并发 | 超出 REJECTED |

参数通过 `AgentConfig` 传入，未指定走场景默认（场景识别看 appId 前缀 `CS_`/`TR_`）。

### 2.3 trace 事件模型（支撑事后回放）

每次状态迁移与每步循环的各阶段发一条结构化事件，事件流落库（§4 表）+ 可经 SSE 实时推送（客服界面进度展示用）。

事件字段（统一）：`run_id / tenant_id / step_no / phase(PLAN|TOOL_CALL|TOOL_RESULT|REFLECT|HITL) / tool? / argsHash / resultHash / llmTokens / latencyMs / decision / ts / trace_id`

回放 = 按 run_id 顺序重放事件流，重建当时上下文（argsHash/resultHash 内容体量大会超库限，正文存对象存储桶，库只存哈希探针）。

### 2.4 优雅终止结构

非 COMPLETED 结束时统一返回：`{status, partialAnswer, unfinishedReason, completedSteps, link}`，其中：
- `partialAnswer`：已完成部分整理输出（不让用户空手而归）
- `unfinishedReason`：能解释给用户的终止原因（"已尝试 10 步仍未解决，建议转人工"）
- `link`：回放链接（管理端）

---

## 3. 接口契约（AgentRuntimeService，L3 对外）

| 方法 | 说明 |
|------|------|
| `AgentRun submit(String tenantId, String appId, String task, AgentConfig config)` | 创建运行，返回 runId（CREATED） |
| `AgentRunDetail get(String tenantId, String runId)` | 运行详情（状态/步数/预算/trace 摘要） |
| `void cancel(String tenantId, String runId)` | 取消运行（仅 RUNNING 可取消） |
| `List<AgentTraceEvent> replay(String tenantId, String runId)` | 按序回放事件流 |
| `Flux<String> stream(String tenantId, String runId)` | SSE 实时事件（W4 客服界面联调用） |
| `void retry(String tenantId, String runId)` | 仅 TIMEOUT/FAILED 可重试（新建 run，复用原配置） |

错误经 `GlobalExceptionHandler` 统一包装，写操作 HITL 挂起事件包含 `approvalId`。

---

## 4. 数据结构（建表，模板见 docs/templates/TABLE-DESIGN-TEMPLATE.md）

### 4.1 `t_agent_run`（运行主表）

| 字段 | 类型 | 说明 |
|------|------|------|
| `run_id` | BIGSERIAL PK | 运行号 |
| `tenant_id` | VARCHAR(64) NOT NULL | 租户隔离（索引） |
| `app_id` | VARCHAR(64) NOT NULL | 场景标识 CS_/TR_，决定默认护栏 |
| `task` | TEXT NOT NULL | 任务原文 |
| `status` | VARCHAR(24) NOT NULL | §2.1 状态枚举 |
| `max_steps` / `token_budget` / `timeout_ms` / `loop_threshold` | INT | 实参快照（配置演进不追溯） |
| `steps_done` / `tokens_used` | INT | 实时累计（每步原子更新） |
| `trace_id` | VARCHAR(64) | 全链路 Trace 关联 |
| `approved_at` / `finished_at` / `created_at` | TIMESTAMPTZ | 时间轴 |
| `unfinished_reason` | TEXT | 优雅终止说明（非 COMPLETED 时） |

索引：`(tenant_id, created_at)`、`(status, created_at)`。保留策略：运行完成后 90 天归档（任务文本含业务信息，需保留；老数据压缩进冷存储）。

### 4.2 `t_agent_step`（每步事件明细）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | BIGSERIAL PK | |
| `run_id` | BIGINT FK | 回放关联 |
| `step_no` | INT NOT NULL | 步骤序号 |
| `phase` | VARCHAR(16) NOT NULL | PLAN/TOOL_CALL/TOOL_RESULT/REFLECT/HITL |
| `tool_name` | VARCHAR(64) | 工具名（TOOL_* 阶段） |
| `args_hash` / `result_hash` | VARCHAR(64) | SHA-256 探针（正文存对象存储） |
| `llm_tokens` | INT | 本步 LLM 消耗 |
| `latency_ms` | INT | 本步耗时 |
| `decision` | TEXT | LLM 原始 thought（截断 2K） |
| `created_at` | TIMESTAMPTZ | |

索引：`(run_id, step_no)` 唯一。保留策略：90 天随主表归档。

> 说明：事件流可推对象存储作长期血缘（P3 血缘平台接入点），库内仅保 90 天可查。

---

## 5. 备选方案与放弃理由

| 备选 | 放弃理由 |
|------|----------|
| Spring AI 自带 ToolCallback 简单循环 | 无状态管理、无护栏、无 trace——重写核心逻辑与其耦合不如自研（架构铁律第 4 层 API 框架独立） |
| LangChain4j Agent | 平台已定 Spring AI（README 技术栈），且同理不满足状态/护栏/回放要求 |
| 直接上 Workflow 引擎（W6 提前） | 探索性任务（客服/查单）需要自由循环而非固定 DAG；先 Runtime 后 Workflow 与排期一致 |
| Flowable/Camunda（Workflow 引擎） | W6 再评估，本期不引入重型流程引擎 |

## 6. 对横切面的影响

- **租户隔离**：run 与 step 均按 tenant_id 隔离，API 强制校验 tenant 归属（X-Tenant-Id 与 run 不匹配 → 403）
- **血缘**：run_id 挂 trace_id，step 挂工具调用，W5 工具层落幂等键后可回溯"哪步调了哪个工具、花了多少 token"
- **成本治理**：tokens_used 逐步累计落库，按租户/应用聚合查询（P2 成本看板）
- **保留策略**：见 §4；会话原始记录 180 天（客服渠道政策），run 数据 90 天归档——审计要求两者通过 run_id ↔ 会话 id 关联

## 7. 验收用例（3 个，实现后跑单测）

| # | 场景 | 期望 |
|---|------|------|
| AC-1 | 正常完成：客服问"查一下我的最近订单"→ 工具查询 → 总结回答 | 3-5 步内 COMPLETED，trace 完整 |
| AC-2 | 护栏触发：任务强制写操作（如发送优惠券）但未审批 | 停 HITL，写工具未执行；10 步内未收口 → TERMINATED + 优雅输出 |
| AC-3 | 预算耗尽：tokenBudget 设为 1K | BUDGET_EXHAUSTED，返回已完成部分 + 未完成说明 |

## 8. 实施计划（批准后）

1. 表结构迁移（t_agent_run/step）+ 仓库层
2. 状态机核心 + 护栏检查器（四重熔断 + REJECTED 入口校验）
3. Spring AI 工具接入（当前 LlmGateway 扩展 tool 参数）+ JSON 输出强约束
4. trace 事件流 + 回放 API + SSE stream
5. 4 分支单测（AC-1/2/3 + 超时/取消/重试）→ W4 周末检查点

---

## 9. 变更历史

| 版本 | 日期 | 变更 | 说明 |
|------|------|------|------|
| v1.0 | 2026-08-31 | 初版 | 用户批准后按 §8 实施（2026-08-31） |
| v1.1 | 2026-08-31 | 实现回写（偏离说明） | ① **HITL 审批超时**：v1 审批挂起 10 分钟无响应 → TERMINATED（补充 §2.1 未定义细节）；② **t_agent_run 增列** `pending_tool/pending_args` 存待审批写操作（原 §4.1 无审批字段，表决于单表 + status=WAITING_APPROVAL，不另建审批表）；③ **token 计量**：`generateStructured` 不返回 usage，v1 用固定估算 `LLM_STEP_TOKENS=500/步` 驱动 budget 语义（L6 计量拦截器落地后接真实值）；④ **stream 仅实时事件**：历史事件走 replay 接口（SSE 不做回放合并）；⑤ 工具选择与参数校验 v1 用 JSON 模式手动解析执行（不接 Spring AI 原生 tool_calls，见 LlmGateway javadoc），`INVALID_TOOL_MAX=2` 次非法决策 → TERMINATED；⑥ 实现时补充 `WAITING_APPROVAL` 挂起重启恢复：启动时置 FAILED（审批 future 内存态不可持久化） |