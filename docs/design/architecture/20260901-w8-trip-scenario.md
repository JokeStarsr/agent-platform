# 商旅 Agent 场景接入设计（W8：真实编排压测基础）

> 版本：v1.1 ｜ 状态：**已批准**（2026-09-01 用户审批通过，实现完成） ｜ 依据：《开发排期》W8、docs/design/architecture/20260831-workflow-engine.md（W6 已批准）、20260901-memory-context.md（W7 已批准）、20260901-tool-engine.md（W5 已批准）

---

## 1. 设计目的

**要解决的问题**：W6 商旅流程（trip_booking Mock 版）只是骨架——**无政策规则、无违规拦截、无支付门控、无场景回归能力**。周末检查点要求：商旅 30 任务成功率 ≥80%、**政策违规 100% 被拦截**、**支付无人工确认不可达（硬测试）**。三者都依赖"政策规则确定性校验 + 支付权限硬门 + 场景级回归套件"，这些是 AI 侧可交付的基建。

**不做会怎样**：政策靠 LLM 自省，违规时有漏网（检查点直接不达标）；支付工具与写工具同权限，无"无确认不可达"的硬保证；无回归套件则"30 任务成功率"无法量测，badcase 修复无从回归。

**范围**：本设计实现 **W8 AI 侧全部**——①差旅政策知识包（`t_policy_rule` 规则表 + `policy_check` 确定性校验工具 + 政策文档入组织记忆）；②升级提醒接线（escalationUrl 回调，补齐 W6 v1.1 预留）；③支付门控（`pay_order` 以 PAYMENT 权限注册，引擎硬 403）；④商旅场景回归套件（30 任务 JSON + Python runner 成功率统计 + 硬测试断言）；⑤trip_booking 编排 v2（方案生成后插入 policy_check 校验节点）。

**不做**：支付双人复核放开（P2 末期，W5 决议 PAYMENT 仅注册不开）；CONDITION/SUBFLOW 运行时（P3，违规拦截用"工具抛错→节点 FAILED"实现）；政策规则内容（业务规则由用户编写，v1 提供种子规则演示）；人群调试 badcase（回归套件跑出 baseline 后人工标注再修）。

---

## 2. 核心模型

### 2.1 政策规则（结构性、确定性、可配置）

**为什么不能靠 LLM**：密集流程中 LLM 偶发漏判一项规则即违规放行——检查点要求 100% 拦截，必须用确定性规则引擎式校验工具。

`t_policy_rule`（每租户一套规则，按 dimension 分维度校验）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | BIGSERIAL PK | |
| `tenant_id` | VARCHAR(64) NOT NULL | 租户隔离（上一版记忆红线延续） |
| `rule_code` | VARCHAR(64) NOT NULL | 规则唯一标识（FLIGHT_CLASS/HOTEL_STAR/BOOK_AHEAD/AMOUNT/…） |
| `dimension` | VARCHAR(32) NOT NULL | 校验维度：flight_class / hotel_star / book_ahead / amount |
| `operator` | VARCHAR(16) NOT NULL | `in`（枚举允许）/ `le` / `ge` / `deny`（禁止项） |
| `threshold_value` | VARCHAR(64) NOT NULL | 阈值（如 "经济舱"、"3"、"5000.00"） |
| `message` | VARCHAR(255) NOT NULL | 违规提示（给 LLM/转人工） |
| `enabled` | BOOLEAN DEFAULT TRUE | 停用不停删除（治理） |
| `source` | VARCHAR(255) | 来源政策文档（血缘→知识包） |
| `created_at` / `updated_at` | TIMESTAMPTZ | |

索引：`(tenant_id, dimension)`。

### 2.2 policy_check 校验工具（L5，READ 权限）

- 入参：`{flightClass?, hotelStar?, bookAheadDays?, totalAmount?}`（方案候选值）
- 逻辑：按租户加载启用规则，逐维度校验：
  - `flight_class.deny`/`in`：舱位枚举允许/禁止
  - `hotel_star.le`：星级 ≤ 阈值
  - `book_ahead.ge`：提前天数 ≥ 阈值
  - `amount.le`：总金额 ≤ 阈值（超阈值标 `needsApproval=true`）
- 出参：`{compliant: bool, violations: [{ruleCode,message}], needsApproval: bool}`；**违规即抛 BizException(400 "POLICY_VIOLATION: …")** → Workflow 节点 FAILED → 流程 FAILED（100% 拦截，确定性，不依赖 LLM）
- 注册进 ToolRegistry（W5 引擎统一 schema 校验/超时/幂等——READ 无幂等负担）

### 2.3 政策知识包入组织记忆

- 政策文档（Markdown）走既有 `/api/rag/index` 入库（组织记忆 = RAG collection，W7 已定义"组织记忆复用 RAG"）
- `source` 列把 `t_policy_rule` 与文档关联（血缘：规则 ← 文档章节）

### 2.4 支付门控（硬 403）

- `pay_order` 工具注册权限 = **PAYMENT**：ToolEngine 对 PAYMENT 直接 `403 支付级工具未开放`（W5 已实现该分支，本轮注册工具即天然不可达）
- **硬测试断言**：任何流程/直接调用 `pay_order` → 403，**无人工确认不可达 = 工具层硬保证**（比"编排约定"更强）
- 支付确认 HITL 界面要素（人工任务）预留：pay 场景的 HUMAN 节点 content 含 `preview`（API/参数/金额/影响范围）字段模板，P2 末期放开 PAYMENT 时复用人工节点 → 双人复核

### 2.5 升级提醒接线（补齐 W6 预留 escalationUrl）

- trip_booking 流程定义增加 `"escalationUrl"` 配置（人工节点超时升级回调）
- WorkflowEscalationScanner 升级时：post `{instanceId, nodeId, escalationTime, flowId}` 到 escalationUrl（幂等：escalated_at 已置不重发；失败下一轮重试）——把 W6 v1.1「留待接线」闭环

### 2.6 编排 v2（trip_booking 增加校验节点）

```
n1 policy_query → n2 PARALLEL(比价) → n3 LLM(方案生成)
→ n3b policy_check(新增：方案合规校验)  ← 违规 → FAILED（100% 拦截）
→ n4 HUMAN(确认) → n5 book_order → n6 notify_user
```
- 校验失败＝流程 FAILED（含 violations 明细于 errorMsg），不进入人工确认（省钱省时）
- 政策宽容场景：`needsApproval=true`（金额超阈值）不失败，进入 n4 人工确认（审批金额阈值逻辑）

---

## 3. 接口契约

### 3.1 PolicyRuleService（L4 能力层脚手架，供工具读取）

| 方法 | 说明 |
|------|------|
| `List<PolicyRule> rules(String tenantId, String dimension)` | 按维度取启用规则（policy_check 工具用） |
| `List<PolicyRule> allRules(String tenantId)` | 管理端/测试用 |
| `PolicyRule upsert(String tenantId, PolicyRule)` | 规则维护（v1 走种子脚本/管理接口） |

### 3.2 回归套件（scripts/eval/trip_runner.py，与 golden_set_runner 同风格）

```jsonc
// scripts/eval/trip-tasks.json —— 30 任务（v1 种子 12 条演示，业务侧补充至 30）
{ "taskId":"t01", "task":"帮我订上海3天行程", "input":{"city":"上海","days":3,...},
  "expect":{"compliant":true,"needsApproval":false} }
```
- runner 动作：POST /api/workflow/instances（flowRef=trip_booking）→ 轮询至 WAITING_APPROVAL → 自动 approve(true) → 轮询终态 → 统计
- 输出：成功率 = COMPLETED/总数；违规任务（expect.compliant=false）必须 FAILED；**policy 违规拦截 100%** 与 **payment 不可达** 为独立硬断言（exit code≠0 阻断）

### 3.3 硬测试断言（trip_guarantees_test.py）

1. 政策违规：任一任务方案含违规值 → 流程 FAILED 且 errorMsg 含 POLICY_VIOLATION
2. 支付不可达：直接调工具引擎/流程定义含 pay_order → 403

---

## 4. 数据结构

### 4.1 `t_policy_rule`（见 §2.1，schema-policy.sql 幂等 DDL）

### 4.2 政策文档

组织记忆复用 RAG `vector_store`，不新增表；`source`=文档名+章节。

### 4.3 编排变更

不新增表；trip_booking 流程 v2 定义在 `resources/workflows/trip_booking.json`（增加 n3b policy_check 节点 + escalationUrl）。

---

## 5. 备选方案与放弃理由

| 方案 | 结论 | 理由 |
|------|------|------|
| 政策校验靠 LLM 自省（Prompt 约束） | ❌ | 检查点要求 100% 拦截，LLM 偶发漏判即违规放行；确定性工具才是硬保证 |
| 校验放 book_order 工具内部 | ❌ | 违反 W5「工具不得吞掉校验职责、引擎统一」精神；校验与执行解耦便于流程调试与警示 |
| 违规→CONDITION 分支转人工（需 CONDITION 运行时） | ❌ | CONDITION 运行时属 P3；v1 违规=FAILED 已满足「被拦截」语义，条件分支留 P3 实现后升级 |
| pay_order 用 WRITE 权限 + 编排约定人工前序 | ❌ | "编排约定"可被忘改；PAYMENT 硬 403 从工具层保证不可达，字符串硬测试即可断言 |
| 支付双人复核本期放开 | ❌ | W5 决议 PAYMENT 仅注册不放开，双人复核留 P2 末期 |

## 6. 对横切面的影响

- **租户隔离**：t_policy_rule 行级 tenant_id；policy_check 只读本租户规则
- **血缘**：规则 `source` 关联政策文档切片；违规事件入节点历史（errorMsg 含 violations）
- **成本治理**：违规流程在人工确认前 FAILED，省去人工环节与后续步骤 token/时间
- **审计**：回归套件全流程调用走既有 AuditLogAspect；升级回调事件留痕

## 7. 验收用例（对应周末检查点）

| # | 场景 | 期望 |
|---|------|------|
| AC-1 | **政策违规 100% 拦截**：任务方案含违规（超星级/金额超限/舱位禁止） | 流程 FAILED，errorMsg 含 POLICY_VIOLATION+规则明细；30 任务中违规任务 100% 如此 |
| AC-2 | **支付不可达**：任何方式调用 pay_order | 403「支付级工具未开放」；无人工确认不可达（工具层硬保证） |
| AC-3 | **合规任务走通**：合规方案 | COMPLETED；approved 的人工确认节点正常 |
| AC-4 | **升级提醒**：人工节点超时（短额度模拟） | HUMANS_ESCALATED 事件 + escalationUrl 收到回调（幂等一次） |
| AC-5 | **成功率统计**：跑 30 任务 | runner 输出成功率（W8 目标 ≥80%，v1 baseline 报告为准） |

## 8. 实施计划（批准后）

1. `schema-policy.sql`（t_policy_rule）+ PolicyRuleRepository/PolicyRuleService + 种子规则脚本（4 维各 1-2 条演示）
2. `PolicyCheckTool`（L5 READ，$2.2 语义）注册进 ToolRegistry
3. trip_booking v2（n3b 节点 + escalationUrl）+ pay_order（PAYMENT）注册
4. 升级扫描器 escalationUrl 回调接线（W6 TODO 闭环）
5. 回归套件：trip-tasks.json（种子 12+）+ trip_runner.py（成功率统计）+ trip_guarantees_test.py（AC-1/2 硬断言）
6. 本地实跑：跑种子任务，报告 baseline，标出需人工排查的 badcase

---

## 9. 变更历史

| 版本 | 日期 | 变更 | 说明 |
|------|------|------|------|
| v1.0 | 2026-09-01 | 初版 | 交用户审批（铁律：批准前不写实现） |
| v1.1 | 2026-09-01 | 实现回写（用户批准实现完成） | ① **数值阈值解析修复**：阈值列为 VARCHAR，工具原 `num()` 只接受 Number 致星级/天数/金额维静默跳过——改 `thresholdNum()` 解析字符串（PolicyCheckToolTest 捕获）；② **钱包/基线说明**：本地 LLM 通道（sub2api→zen localhost:8180）仍 401 INVALID_API_KEY，30 任务 baseline 待 LLM 通道就绪后跑（runner/guarantees 脚本已就绪，W8 目标≥80%）；③ **AC-1 真 PG 端到端探针**（无 LLM 变体）：违规(头等舱) → FAILED + `POLICY_VIOLATION:[{ruleCode=FLIGHT_CLASS,…}]`，合规 → 人工确认 → COMPLETED；④ **PolicyRuleService/管理接口本期未建**：种子规则走 schema-policy.sql 幂等 seed（ON CONFLICT），管理接口留 P3；⑤ **escalationUrl 接线落地**：扫描器 POST 回调 2xx 才置 escalated_at（失败下轮重试幂等），无回调配置仍标记；⑥ pay_order（PAYMENT）注册，PaymentGateTest 验证引擎硬 403 |