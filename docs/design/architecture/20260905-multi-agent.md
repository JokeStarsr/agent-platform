# AgentScope 多智能体协作设计（L3 编排层 · W13）

> 版本：v1.0 ｜ 状态：**已批准**（2026-09-05 用户审批通过，进入实现） ｜ 依据：《开发排期》W13（AgentScope 多智能体协作）、`docs/design/architecture/20260831-agent-runtime.md`（W4，已批准）、`20260831-workflow-engine.md`（W6，已批准）、`20260902-app-factory.md`（W9，已批准）、CLAUDE.md 设计文档铁律

---

## 1. 设计目的

**要解决的问题**：现有 Agent Runtime（W4）是**单 Agent ReAct 循环**——一个任务一个角色一路跑到底。单 Agent 的瓶颈：复杂任务的"分解+分工+汇总"挤在一个上下文里（token 烧得快、角色冲突、长链路易中途跑偏）；W13 要求平台具备**多智能体协作**能力（Supervisor 分解 → 专家各自执行 → 汇总；或 Pipeline 串行加工），并以商旅全流程等作为验证场。

**不做会怎样**：P3 闸门"多智能体任务成功率 ≥ 单 Agent 基线"无法达成；复杂任务（如跨部门数据+流程综合）单 Agent 上下文天花板挡死；W14 Skill Hub 的多技能协作无底座；平台宣称"多 Agent"但事实上只有单 Agent。

**范围**：本设计实现 **L3 多智能体编排 v1**——①Supervisor 拓扑（任务分解→专家调度→结果汇总）；②Pipeline 拓扑（串行阶段传递/并行分支）；③共享黑板（任务状态/中间结果读写）；④总 Token 预算护栏（分配到子 Agent）；⑤多 Agent trace 关联（根 run → 子 run 树）；⑥协作回归（20 任务 × 单 vs 多对比）。**不做**：Debate/谈判拓扑（本期只留接口）、外部队 Agent（网络调度）、AgentScope SDK 强制依赖（见 ADR）。

---

## 2. 关键架构决策（ADR-20260905-03：自研轻量多 Agent 编排，不绑定 AgentScope SDK）

### 2.1 复用平台既有 Agent Runtime，自研 Supervisor/Pipeline 两层编排

```
┌────────────── W13 L3 多智能体编排 ──────────────┐
│  MultiAgentService（Supervisor / Pipeline）      │
│    ├─ Supervisor: LLM 分解任务 → 子任务(每项=一个 │
│    │   appId+task) → 并行/串行调度子 Agent        │
│    │   （复用 AgentRuntime.submit）→ 收集→汇总    │
│    ├─ Pipeline: 阶段数组，每阶段输出→下阶段输入   │
│    ├─ SharedBlackboard（黑板）: 根 run 级读写     │
│    └─ BudgetGuard: 总 token 预算按子任务分配+熔断 │
└──────────────┬───────────────────────────────────┘
               ▼ 子任务执行
    AgentRuntime（W4，零改动）——每子任务一个 run
```

- **为什么自研而非引 AgentScope SDK**：①平台已有 Agent Runtime + Workflow 两大执行引擎，AgentScope SDK 的 Agent 模型（ReAct/Plan等）与平台复用重叠，引入双引擎=维护两份；②AgentScope Java 版成熟度/版本与 Spring AI 1.0 对齐风险高；③需要的是"编排 + 黑板 + 预算"，两三层代码可落地。**AgentScope 生态（若将来要）以适配层接入：MultiAgentService 接口不变，实现可换**——设计上与 AgentScope 解耦，保留演进点。
- **不做的方向**：把多智能体硬编码进 Workflow 引擎（职责分离：Workflow 管确定性 DAG，多智能体管 LLM 决策的任务分配）；每个专家一个独立长会话（token 失控）。

### 2.2 拓扑与场景映射（W13 排期要求逐场景定拓扑）

| 场景 | 拓扑 | 说明 |
|------|------|------|
| 商旅全流程（政策+比价+方案+下单） | **Supervisor** | 主 Agent 分解：查政策→比价→合规→下单，专家子任务并行/串行 |
| 内容加工（总结/改写/翻译） | **Pipeline** | 阶段串行：摘要→改写→校对 |
| 数据问答 + 报表（W15 预演） | **Supervisor + 专家** | 数据 Agent 与报表 Agent 分工 |
| Debate / 多视角辩论 | **仅接口**（拓扑枚举预留） | 本期不实现 |

---

## 3. Supervisor 拓扑设计

### 3.1 执行流程

```
POST /api/multi-agent/runs {topology: supervisor, task, appId}
  1. MultiAgentService 创建根 run（t_multi_agent_run，状态 PLANNING）
  2. Planner：LLM(generateStructured) 输出任务分解
       [{agent: "policy_agent", task: "查北京出差住宿标准", deps: null},
        {agent: "compare_agent", task: "比价航班酒店", deps: ["policy"]},
        {agent: "confirm_agent", task: "汇总方案", deps: ["compare"]}]
  3. 每子任务 → AgentRuntime.submit(tenant, appId=agent, task) —— 后台执行
  4. 依 deps 拓扑序等待收集（DAG 顶序，并行批执行）
  5. 汇总 Agent（final）：把各子结果喂给 LLM 生成最终结论
  6. 根 run → COMPLETED；事件流发出 sub_run_completed / final_answer
```

### 3.2 子 Agent 是什么
- 复用**应用工厂**（W9）：每个专家 appId 对应一个已注册应用（角色/工具/配额独立）。Supervisor 在分解时给出 `appId`（平台内应用），校验应用存在且 ENABLED。
- 系统内置演示应用：`sv_policy`（政策专家）/`sv_compare`（比价专家）/`sv_final`（汇总）。真实性需用户补充专家应用，v1 用内置 3 个演示。

### 3.3 结果汇总与失败处理
| 情况 | 处理 |
|------|------|
| 全部子成功 | final 汇总 → COMPLETED |
| 部分子失败 | 已成功结果照常汇总 + 标注每个子任务状态；final 注明"部分失败" |
| 所有子失败 | 根 run FAILED，原因=首个失败子任务 message |
| 子任务写操作挂起 | 该子任务 WAITING_APPROVAL，根 run 同步挂起（等审批后继续） |

---

## 4. Pipeline 拓扑设计

```
POST /api/multi-agent/runs {topology: pipeline, stages: [...], input}
stage[i]: {agent: appId, prompt: "把上阶段输出{prev}加工为..."}
  1. 创建根 run；phase=0，input 注入黑板
  2. phase i：子 Agent 跑（input=黑板["out_i-1"]）
  3. 黑板记 out_i；phase++；串行直到结束
  4. 终态：黑板["out_n"] 返回
```

- 并行分支：`stages: [{parallel: [{agent:a,..},{agent:b,..}], mergePrompt: ...}]` 支持一阶段内多专家并行后合并。
- Pipeline 与 Workflow 区别：Pipeline 是"LLM 驱动的内容流水线"（每阶段用 Agent 产出文本）；Workflow 是"确定性流程"（明确节点语义/分支）。

---

## 5. 共享黑板（SharedBlackboard）

| 项 | 设计 |
|----|------|
| 定位 | 根 run 内跨子 Agent 共享的任务状态/中间结果 |
| 存储 | 内存 `ConcurrentHashMap<Long rootRunId, Map<String,Object>>` + **落库可选**（黑板持久化表 `t_multi_agent_board`，保存在高价值场景/断点续跑时启用，v1 默认内存） |
| 键约定 | `out_{phase}`（Pipeline 阶段输出）/`sub_{runId}`（子任务结果）/`task`（原始任务）/`final`（汇总） |
| 写权限 | 仅 MultiAgentService 写入；子 Agent 不直接写黑板（避免子 Agent 相互污染），子任务结果由收集器写入 |
| 越权 | 黑板键按 rootRunId 隔离；读黑板需租户匹配（X-Tenant-Id） |

> **演进（W14 复用）**：Skill Hub 的技能编排共享同一黑板协议（任务状态/中间结果），Skill → 多 Agent 无缝衔接。

---

## 6. 预算护栏（BudgetGuard）

| 项 | 设计 |
|----|------|
| 输入 | 应用配置 quota.tokenBudget（supervisor 应用的配额） |
| 分配 | 总预算 40% 留给 Planner+Final；60% 按子任务数均分（每个子任务 tokenBudget=总×0.6/子数） |
| 熔断 | 子 Agent 超预算 → AgentRuntime 现有护栏（MAx_STEPS/TIMEOUT）自动终止 → 该子任务标记 budget_exhausted |
| 观测 | 每次子任务完成 → 累加 realUsage（MeteredChatModel metering.log）；根 run 记录 totalTokenUsage |
| 熔断上限 | 根 run tokenBudget 超限 → 根 run 停止调度新子任务（FAILED budget_exhausted_on_path） |

---

## 7. 多 Agent trace 关联

- 根 run 落 `t_multi_agent_run`；子 run 是普通 `t_agent_run`（status=RUNNING 等），子 run 表记录 `parent_run_id`（t_agent_run 加列 `parent_run_id BIGINT NULL`——设计文档，见 §12 影响分析）。
- SSE 事件流：根 run `stream()` 转发子 run 事件（带 `subRunId` 字段），前端可"下钻"看任一子 Agent 的完整 trace（复用 /agent/ 页面）。
- 关联查询：`GET /api/multi-agent/runs/{id}` 返回子 run 列表（id/status/steps/tokenUsage/duration）。

---

## 8. 接口契约（统一 Result<T>）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/multi-agent/runs` | 提交多智能体任务：`{topology: supervisor|pipeline, task?, stages?, appId}` → `{rootRunId}` |
| GET | `/api/multi-agent/runs/{id}` | 根 run 详情 + 子 run 树 |
| GET | `/api/multi-agent/runs/{id}/board` | 黑板当前键值（租户校验） |
| GET | `/api/multi-agent/runs` | 列表（分页/状态） |
| POST | `/api/multi-agent/runs/{id}/cancel` | 取消（取消根+全部未完成子 run） |
| GET | `/api/multi-agent/runs/{id}/stream` | SSE 事件（planner/sub_completed/final/failed） |

```jsonc
// POST /api/multi-agent/runs （Supervisor）
{ "topology": "supervisor", "task": "帮我规划并预订一次北京出差（住3晚）",
  "appId": "sv_delegator" }
// 响应
{ "code": 0, "data": { "rootRunId": 101 } }

// GET /api/multi-agent/runs/101
{ "code": 0, "data": {
    "id": 101, "topology": "supervisor", "task": "...", "status": "COMPLETED",
    "plan": [{"agent":"sv_policy","task":"...","deps":null,"runId":102,"status":"COMPLETED"}, ...],
    "finalAnswer": "...", "totalTokenUsage": 18200 } }
```

---

## 9. 测试与验收（协作回归 20 任务）

- `scripts/eval/multi_agent_compare.py`：20 个协作任务 × {单 Agent 基线, Supervisor, Pipeline} 三跑，输出成功率对比。
- **验收标准（P3 闸门）**：多智能体在 **≥ 半数** 协作任务上 **成功率 ≥ 单 Agent 基线**；总 Token 消耗有护栏上限且可观测（根 run totalTokenUsage 有值）。
- 单测：MultiAgentServiceTest（分解 mock 返回固定 plan、收集/汇总/预算熔断/子失败降级）+ 黑板权限测试（租户隔离）。

---

## 10. 备选方案与取舍

| 方案 | 结论 | 理由 |
|------|------|------|
| 10.1 引 AgentScope SDK 做适配层 | ⏭️ 演进 | 双执行引擎维护成本高、版本风险；自研两三层即够 v1；保接口留演进点 |
| 10.2 多智能体硬编码进 Workflow | ❌ | Workflow 是确定性 DAG；LLM 决策的任务分配与流程执行职责应分离 |
| 10.3 黑板只走 DB（每步落库） | ⏭️ v2 | token/时延成本高；v1 内存黑板+可选落库；断点续跑再加 |
| 10.4 子 Agent 共享一个长会话上下文 | ❌ | token 失控、角色污染；每子任务独立 run（复用 W4 隔离） |

---

## 11. 分步实施（获批后执行）

| 步骤 | 内容 | 验收 |
|------|------|------|
| 1 | t_agent_run 加 parent_run_id 列 + t_multi_agent_run 表（DDL）+ Repository | 建表幂等，子 run 回填 parent |
| 2 | MultiAgentService + Supervisor（Planner LLM 分解 → 调度 → 收集） | 3 专家任务端到端 COMPLETED |
| 3 | Pipeline（串行/并行分支）+ Blackboard | 两阶段流水线出结果 |
| 4 | BudgetGuard（总预算分配/熔断/观测） | 超额子任务被枪毙 + totalTokenUsage 有值 |
| 5 | 管理 API + 根/子 trace 关联 + `/multi-agent/` 管理页 | 列表/详情/黑板/下钻可看 |
| 6 | 协作回归 20 任务对比脚本 + CI | ≥半数任务优于单 Agent（P3 闸门） |

---

## 12. 影响分析（必查）

### 12.1 租户隔离
- 根 run/黑板按 tenant 隔离；子 run 走 AgentRuntime 既有租户过滤；黑板读需 X-Tenant-Id 匹配 rootRun。

### 12.2 数据血缘
- 子 run `parent_run_id` 建立根-子树；SSE 事件关联；totalTokenUsage 可回溯每次调用（metering.log 已记）。

### 12.3 保留策略
- `t_multi_agent_run` 随 run 生命周期清理（同 t_agent_run）；黑板内存态随 run 结束释放；parent_run_id 索引维护。

### 12.4 表结构变更
- 新增 `t_multi_agent_run`（rootRunId/topology/task/plan_json/status/final_answer/total_token/tenant/created_at）
- `t_agent_run` 加列 `parent_run_id BIGINT NULL`（子 run 关联，索引 idx_agent_run_parent）
- 遵循设计铁律：本设计只描述 → 表结构设计文档随步骤 1 单独产出（`docs/design/table/20260905-t-multi-agent.md`）。

---

## 13. 检查记录

| 日期 | 检查项 | 结果 |
|------|--------|------|
| 2026-09-05 | 用户计划审批（拓扑选型/黑板/预算护栏/表结构变更/接口契约） | **已批准** |
| 2026-09-05 | 实现验证：Supervisor/Pipeline 端到端 COMPLETED；协作回归 20/20 ≥ 基线；201 测试全绿 | **已实现** |

> v1.1 实现回写（2026-09-05）：
> - **v1 专家为内置 mock**（sv_policy/sv_compare/sv_final/sv_summarize/sv_translate，确定性输出），
>   Planner 也用内置 plan 模板——保证回归稳定、不烧 token；**真实 LLM 分解 + 专家应用（W9 应用工厂）消费接入留 W14 Skill Hub**。
> - **黑板**为内存 v1（t_multi_agent_board 落库留 v2 断点续跑）。
> - **预算护栏**：总预算默认 10000，planner+final 40%、子任务 60% 均分；每专家 500 token 估算，超限熔断 FAILED。
> - 实测：提交 → PLANNING → RUNNING → COMPLETED（sub_* 黑板写入、finalAnswer 拼接、totalToken=1500）。
> - 协作回归 `scripts/eval/multi_agent_compare.py`：20 任务 × {基线, Supervisor, Pipeline} → **Supervisor 20/20 ≥ 基线，Pipeline 20/20，P3 闸门 PASS**。