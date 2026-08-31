# 工具调用引擎设计（L5 工具协议层）

> 版本：v1.1 ｜ 状态：**已批准**（2026-08-31 用户审批通过，实现完成） ｜ 依据：《架构设计说明书》4.3.4（Harness 执行环境/工具条款）、L5 工具协议层铁律（写操作必须携带幂等键）、CLAUDE.md、《开发排期》W5

---

## 1. 设计目的

**要解决的问题**：Agent Runtime（W4）v1 的工具由 `AgentTool` SPI 直接注入 Runtime，无元数据治理、无 schema 校验、无幂等保障。P2 闸门要求"写操作 100% 走 HITL、幂等功能测试全绿"——当前 send_coupon 重复调用无拦截，多发一张券没有机制发现。

**不做会怎样**：工具随个数增长失控（无注册表/无描述规范/不可发现）；写操作重放（网络重试、Agent 循环重试、人工重复操作）造成重复扣费/重复发券；参数幻觉无法在入口拦截，依赖模型自律。

**范围**：本设计实现 **L5 工具协议层 v1**——工具注册中心 + 元数据规范 + Function Call 引擎（Schema 校验/错误重试/超时）+ 幂等键机制。不做：MCP 协议接入（P3）、沙箱执行（适配团 W6 后 Harness）、支付级权限流转（P2 末期）。Agent Runtime（W4）改为消费本中心的工具注册与执行。

---

## 2. 核心模型

### 2.1 工具注册与元数据

工具注册中心 v1：Spring 注解扫描 + 内存注册表（`ConcurrentHashMap<String, RegisteredTool>`），多例注解自动降级取第一个。注册时携带：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `name` | string | 是 | 全局唯一（`小写下划线`，如 `query_recent_orders`），冲突启动即失败（fail-fast） |
| `description` | string | 是 | 用途说明，直接影响模型选择准确率（W5 排期：亲自写前 3 个） |
| `parameters` | JSON Schema | 是 | 参数结构（org.everit.json.schema 校验） |
| `permission` | enum | 是 | `READ` / `WRITE` / `PAYMENT`（PAYMENT 本期仅注册不放开，双人复核留 P2 末期） |
| `idempotent` | bool | 是 | 写操作必须 true；READ 恒 true 无成本 |
| `timeoutMs` | int | 否 | 单次执行超时，默认 30000 |
| `requiresTenant` | bool | 否 | 执行是否需要租户上下文（多数需要） |

**工具实现约束**（校验在注册时断言）：
- `permission=WRITE/PAYMENT` 的工具**必须**在签名中暴露 `idempotencyKey` 参数，否则启动失败
- 工具不得自行吞掉幂等校验职责，由引擎统一执行（防止实现绕过）

### 2.2 幂等键机制（写操作铁律落地）

**键的来源与格式**：`调用方生成`——Agent Runtime 每次从 LLM 决策得到的写操作调用，分配 `idempotency_key = uuid`（Agent 循环重试同一决策 → 复用同一键；新决策 → 新键）。键格式：`{tenantId}:{appId}:{uuid}`，长度 ≤ 128。

**拦截机制**（`t_tool_invocation` 表 + 内存 LRU 双保险）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `invocation_id` | BIGSERIAL PK | |
| `idempotency_key` | VARCHAR(128) UNIQUE NOT NULL | 幂等键（唯一约束兜底并发） |
| `tenant_id` | VARCHAR(64) NOT NULL | 租户隔离索引 |
| `tool_name` | VARCHAR(64) NOT NULL | |
| `args_hash` | VARCHAR(64) NOT NULL | 参数 SHA-256 |
| `result_payload` | TEXT | 首次成功执行的结果（重放原结果，不二次执行） |
| `status` | VARCHAR(16) NOT NULL | `IN_PROGRESS` / `SUCCESS` / `FAILED` |
| `created_at` / `finished_at` | TIMESTAMPTZ | |

**三种语义**：
1. **键已存在（SUCCESS）** → 直接返回 `result_payload`（幂等重放，不执行）
2. **键已存在（IN_PROGRESS）** → 等待同键首次执行完成（并发去重，等待上限 = 工具 timeout），完成后返回同一结果
3. **键不存在** → 新建 IN_PROGRESS → 执行 → 写 SUCCESS/FAILED

**保留策略**：SUCCESS 结果保留 90 天（审计/对账），FAILED/IN_PROGRESS 30 天清理；过期的幂等重放转"新执行"。

### 2.3 Function Call 引擎（执行管线）

```
ToolCallRequest{tenant, appId, toolName, args, idempotencyKey(写操作必填)}
  → ① 注册表解析（未注册 → 400）
  → ② permission 检查（PAYMENT 禁止执行；WRITE 未带幂等键 → 400）
  → ③ JSON Schema 校验参数（不合法 → 首错文案；Agent 侧带错误重试 ≤2 次，见 Runtime 集成）
  → ④ 幂等拦截（§2.2）
  → ⑤ 执行（超时护栏 timeoutMs，用 CompletableFuture+调度线程）
  → ⑥ 结果规整（Map → JSON；截断 2K 防上下文溢出；敏感字段由工具自标脱敏标记）
```

错误分类：`INVALID_ARGS`(400，可重试) / `TIMEOUT`(504，可重试) / `INTERNAL`(500，不重试) / `NOT_FOUND`(404)。

### 2.4 与 Agent Runtime 集成（W4 改造点）

- Runtime 不再直接持有 `AgentTool` 列表：`system prompt 工具清单` 与 `执行` 均改走 `ToolEngine`
- LLM 决策 `TOOL_CALL` → `ToolEngine.invoke(...)`，**同一决策的写操作重试复用 idempotencyKey**（决策级键以 argsHash 关联，循环内重复调用同参数 → 同键）
- 工具选择失败（未注册）→ INVALID_TOOL 计数沿用 Runtime 现有护栏
- `ToolDescriptor`（L6）废弃，改由注册中心产出描述（ToolEngine 依赖 model 层不变）

---

## 3. 接口契约（ToolEngineService，L5 对外）

| 方法 | 说明 |
|------|------|
| `ToolInvokeResult invoke(String tenantId, String appId, InvokeRequest req)` | 执行工具（含幂等/校验/超时） |
| `List<ToolMeta> listTools(String tenantId)` | 注册表快照（Prompt 组装用） |
| `ToolMeta metaOf(String name)` | 单工具元数据 |
| `Optional<InvocationRecord> replay(String tenantId, String idempotencyKey)` | 查历史调用结果（审计/对账） |

`InvokeRequest{tool, args, idempotencyKey?}`；`ToolInvokeResult{code, message, data, idempotentReplay}`（idempotentReplay=true 表示未真实执行）。错误经 GlobalExceptionHandler 统一包装。租户校验：读工具 requiresTenant 时校验 tenant 归属（工具内数据按 tenant 过滤）。

## 4. 种子工具（3 个，保留客服 2 个 + 新增支付演示）

| 工具 | 权限 | 幂等 | 说明 |
|------|------|------|------|
| `query_recent_orders` | READ | - | 已有（W4）迁移注册 |
| `send_coupon` | WRITE | ✔ | 已有（W4）迁移 + 幂等键参数 |
| `refund_order_partial` | WRITE | ✔ | **新增**：部分退款演示，args 含 `refundAmount` 上限校验（≤ 订单可退金额），幂等键防重复退款 |

## 5. 验收用例

| # | 场景 | 期望 |
|---|------|------|
| TC-1 | 同一 idempotencyKey 连续调用 send_coupon 2 次 | 第二次返回首次结果（idempotentReplay=true），只执行 1 次 |
| TC-2 | 写工具不带幂等键 | 400 拒绝，工具未执行 |
| TC-3 | 参数不符合 Schema（refundAmount=-5 / 超上限） | 400 INVALID_ARGS，错误文案可返回给模型重试 |
| TC-4 | 工具执行超时（mock 工具 sleep 40s，timeout 5s） | 504 TIMEOUT，幂等记录 FAILED，可重试 |
| TC-5 | 未注册工具名 | 404 NOT_FOUND |
| TC-6 | PAYMENT 权限工具调用 | 403 拒绝（本期不放开） |

## 6. 备选方案与放弃理由

| 备选 | 放弃理由 |
|------|----------|
| 直接上 MCP（modelcontextprotocol SDK） | P3 目标；本期先统一内部协议，MCP 适配器 W9 加一层即可，避免早期绑定。若 P3 评估 MCP 成熟可直接平移注册表 |
| LangChain4j/Spring AI 工具机制代持 | 同 W4 Runtime 理由：API 框架独立铁律；且幂等/HITL/权限均是平台语义，第三方机制承载不了 |
| 幂等只靠应用内存（不建表） | 重启丢记录 → 重放防不住；建表一劳永逸（90 天对账窗口） |
| 支付级权限本期放开 | 双人复核/分级审批未就绪，先注册封禁，P2 末期放开 |

## 7. 对横切面的影响

- **租户隔离**：invocation 记录按 tenant 隔离；工具执行上下文携带 tenant（requiresTenant 工具强制校验）
- **血缘**：invocation_id ↔ idempotency_key ↔ run_id（Runtime trace 事件带键）三级关联，审计可回溯"哪次运行/哪次决策调了哪个工具、幂等重放还是真实执行"
- **成本治理**：invocation 记录含执行耗时与结果大小；不做成本的工具（不消耗 LLM token）仅计执行次数
- **保留策略**：见 §2.2；注册表是代码不是数据（启动扫描，无存量）

## 8. 实施计划（批准后）

1. `t_tool_invocation` 表 + 仓库层（键查询/落库/过期清理）
2. `@AgentTool` 注解 + 注册中心（启动 fail-fast 校验：权限×幂等约束）
3. ToolEngineService 实现（校验→幂等→执行→超时）+ 错误分类
4. send_coupon/refund_order_partial 迁移注册 + 幂等键
5. Agent Runtime 集成改造（决策键复用、ToolDescriptor 废弃）
6. 单测 TC-1..6 + 20 任务回归脚本（多步任务成功率统计，复用 Run 单测框架）→ W5 周末检查点（成功率 ≥75%、工具选择准确率 ≥90%）

---

## 9. 变更历史

| 版本 | 日期 | 变更 | 说明 |
|------|------|------|------|
| v1.0 | 2026-09-01 | 初版 | 用户批准（2026-08-31）后按 §8 实施 |
| v1.1 | 2026-09-01 | 实现回写（偏离说明） | ① **注册方式**：放弃 @AgentTool 注解，用 Spring 组件接口扫描（工具类 @Component 实现 AgentTool，构造注入 List 收集），fail-fast 校验=命名小写下划线+全局唯一；② 元数据方法重命名对齐：`argsSchema()→parameters()`、`write()→permission()(ToolPermission READ/WRITE/PAYMENT)`，接口迁 L5 包（orchestration 不得作为工具类型被 L5 依赖）；③ **校验器自研**：everit.json.schema 1.14.0 阿里云镜像缺失、1.5.1 与运行时 org.json 类冲突（NoSuchMethodError）→ 弃用，实现 `ParamSchemaValidator`（仅 type/properties/required/minimum/maximum 五关键字，平台 Schema 由自家工具声明属信任来源）；④ **写工具幂等键实现**：工具签名无需暴露键，由 Runtime 决策级生成（sig=工具+参数哈希→uuid，declared sig 复用）经 InvokeRequest 携带，引擎统一 compose（tenant:app:key）；⑤ **失败重试**：引擎 400 不占键可直接重试、504/409 后 Runtime 换新键重试一次（旧键已 FAILED 不可复用），403/404 跳过该工具；⑥ ToolDescriptor（L6）废弃，工具描述改由注册中心 ToolMeta 产出；⑦ 单测增至：Runtime 11 + 引擎 8（TC-1..6 + 校验细节），全量 31/31 绿 |