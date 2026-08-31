# 记忆服务与上下文组装器设计（L4 能力层）

> 版本：v1.0 ｜ 状态：**待检查**（2026-09-01 提交用户审批） ｜ 依据：《开发排期》W7、CLAUDE.md 七层铁律、《架构设计说明书》4.4（上下文/记忆）、docs/design/eval/20260827-golden-set.md（组织记忆复用 RAG）、20260830-rag-controller.md

---

## 1. 设计目的

**要解决的问题**：P1 至今 LLM 调用是"同 Prompt 拼装"——固定 system + 单轮 RAG，既无**会话级/用户级记忆**，多轮对话无法延续身份与偏好；也无**上下文预算控制**——历史不限堆积、RAG Top-K 偏大、工具结果不截断，长会话迟早爆 token 或被 400 拒。

**不做会怎样**：第二场会话对用户一无所知，无法个性化；超长历史触发上下文超限，降级体验差；token 成本不可预估（P2 成本看板悬空）。

**范围**：本设计实现 **三级记忆 + 预算化上下文组装器 v1**——短期记忆（Redis 会话）、长期记忆（用户画像向量 + 抽取/确认/遗忘）、组织记忆（复用 RAG 独立语义）+ ContextAssembler（Token 预算分配、分级压缩级联）。**不做**：技能记忆、组织级共享记忆写库、记忆跨会话自动回写链路完善（P3）、多模态记忆、Prompt 中心（P2 末期统一）。

---

## 2. 核心模型

### 2.1 三级记忆边界（人工审定的划分）

| 级别 | 存什么 | 存储 | 生命周期 | 谁写 |
|------|--------|------|----------|------|
| 短期 | 当前会话轮次（角色+内容紧凑摘要 / 最近 N 条原文） | Redis（ZSET，原子追加） | 会话 TTL 30 min 滚动；会话结束即失效 | 全自动（每轮落一条） |
| 长期 | 用户画像：偏好/身份/已验证事实/常问 | PG 向量表 `t_user_memory`（按 tenant+user 分片） | 长期保留（软删可遗忘） | 自动抽取器：高置信自动写，低置信待确认 |
| 组织 | 业务知识（政策/文档）——**非用户私人** | 复用 RAG 管道，独立 Collection | 长期，随知识库 | 既有知识库导入 |

划分依据：短期=本会话上下文（临态）；长期=跨会话的用户可复用画像（PII，需可遗忘）；组织=全体用户共享的事实（不含个人 PII）。**PII 记忆只落长期且按用户隔离**，组织记忆不写个人画像。

### 2.2 记忆写入策略（短期全自动；长期自动抽取+低置信确认）

- **短期**：每轮对话结束原子追加一条 `{role, content}` 到会话 ZSET（幂等去重按 content hash），刷新 TTL。
- **长期抽取器**：对助手已回答的会话做 LLM 抽取（JSON 输出），得到候选画像条目 `{field, value, confidence}`：
  - `confidence ≥ 0.7`（身份/数字型等确定事实）→ **自动写入** ACTIVE；
  - `confidence < 0.7` → 进入 **PENDING_CONFIRM**（待用户确认）；人工确认后转 ACTIVE，**累计 2 次一致观察也自动转正**；
  - 用户**可删除/遗忘**（软删 DELETED，90 天后物理清理）——满足 PII 遗忘权。
- 抽取为**异步后台**（不阻塞主响应），失败不影响会话继续。

### 2.3 Token 预算与压缩级联（人工审定的分配表）

默认总预算 `16K` token（可按 appId/模型窗口配置）。分片：

| 分片 | 占比 | 压缩手段（由轻到重） |
|------|------|---------------------|
| System | 10% | 固定角色/护栏；仅必需工具提示 |
| RAG 上下文 | 35% | **降 Top-K**（5→3→2）、每段截断、重排取关键段 |
| 会话历史 | 25% | 保留最近 N 条原文 → **更早摘要化**（LLM 摘要）→ 只剩摘要 |
| 长期记忆 | 15% | 近邻 **降 Top-M**、字段模板化、去重 |
| 工具 Schema | 15% | 按当轮"可能用到"白名单截取描述，不含全量工具 |

**超限压缩顺序**（优先级从高到低，System 保底不入压缩）：
1. 工具 Schema 白名单截取 → 2. 会话历史摘要化 → 3. RAG 降 Top-K & 截断 → 4. 长期记忆降 Top-M。
压缩后仍超 → **拒绝组装并返回能力不足提示**（不静默丢弃关键信息）。

### 2.4 ContextAssembler 组装流程

```
采集(历史/记忆/RAG/工具/System) → 按预算分片核 Token → 逐片回填
 → 任一超限 → 按级联顺序压缩 → 拼装 system+user
 → 返回 {prompt, usage:{各分片 token, 压缩动作[]}}
```
每片 token 用近似计量（字符/token 估算 + LLM 摘要 tokens 计）落库，供成本看板；架构铁律"L4 能力层不绑 API 框架"——装配结果是与框架无关的 Prompt 串，下游 LlmGateway 消费。

---

## 3. 接口契约

### 3.1 MemoryService（L4 能力层接口，供编排层调用）

| 方法 | 说明 |
|------|------|
| `saveShortTerm(tenantId, sessionId, role, content)` | Redis 追加（原子），刷新 TTL |
| `List<ShortTerm> loadShortTerm(tenantId, sessionId, limit)` | 会话历史（近 limit 条） |
| `List<UserMemory> retrieveLongTerm(tenantId, userId, query, topK)` | 画像向量近邻召回（ACTIVE 过滤） |
| `UserMemory saveLongTerm(tenantId, userId, field, value, confidence, source)` | 写长期（自动/确认后） |
| `List<UserMemory> pendingConfirmations(tenantId, userId)` | 待确认画像 |
| `void confirmMemory(tenantId, userId, memoryId, accept)` | 确认/拒绝（接受→ACTIVE，拒绝→DELETED） |
| `void deleteMemory(tenantId, userId, memoryId)` | 用户遗忘（软删，须本人） |
| `List<Map> orgSearch(tenantId, query, topK)` | 组织记忆（复用 RAG search） |

**越权红线**：所有长期/组织方法强制 `tenantId + userId` 归属校验（A 请求携带 B 的 userId 或跨租户 → 403；查询 SQL 必带双条件），由 MemoryService 统一实施，禁止调用方自行拼。

### 3.2 ContextAssembler

| 方法 | 说明 |
|------|------|
| `ContextBundle assemble(ContextRequest{tenantId, userId, appId, sessionId, task, ragHits, toolWhitelist})` | 采集→预算→压缩→拼装，返回 prompt + usage |

---

## 4. 数据结构

### 4.1 `t_user_memory`（长期记忆向量表，PG + pgvector）★涉及新表，模板见 docs/templates/TABLE-DESIGN-TEMPLATE.md

| 字段 | 类型 | 可空 | 说明 |
|------|------|------|------|
| `id` | BIGSERIAL PK | NO | |
| `tenant_id` | VARCHAR(64) | NO | 租户隔离（贯穿所有查询） |
| `user_id` | VARCHAR(64) | NO | 用户隔离（**越权红线字段**） |
| `field` | VARCHAR(32) | NO | 画像字段：preference / identity / verified_fact / frequent_ask |
| `value` | TEXT | NO | 画像内容 |
| `confidence` | DOUBLE PRECISION | NO | 抽取置信度（决定写入是否需确认） |
| `status` | VARCHAR(16) | NO | ACTIVE / PENDING_CONFIRM / DELETED |
| `source` | TEXT | YES | 来源会话/文档（血缘） |
| `embedding` | VECTOR(1024) | NO | 语义向量（智谱 embedding，同 RAG 维度） |
| `created_at` / `updated_at` | TIMESTAMPTZ | - | |

**索引（每个对应真实查询）**：

| 索引 | 字段 | 类型 | 支撑查询 |
|------|------|------|----------|
| idx_mem_user_status | (tenant_id, user_id, status) | 联合 | 按用户查画像（越权校验+过滤） |
| idx_mem_pending | (tenant_id, user_id, status) | 联合 | 待确认列表 |
| embedding 近邻 | (embedding) | IVFFlat | 语义召回（tenant/user 过滤后） |

**租户/用户隔离**：`tenant_id + user_id` 双条件贯穿一切 SELECT/DELETE；API 层再校验归属（403）。这是红线，检查必查项（模板第 5/6 节）。

**保留策略**：ACTIVE 长期保留；DELETED 软删 90 天后物理清理；PENDING_CONFIRM 30 天未处理自动转 DELETED。PII 字段（value）可按用户级联清除（遗忘权）。

### 4.2 Redis 短期记忆（不建表）

`key = mem:short:{tenantId}:{sessionId}`，`ZSET`：member=`{role, content}`，score=时间戳（原子 ZADD 追加），TTL 30 min 每次写入滚动刷新。删除=会话过期自动过期。

### 4.3 组织记忆

复用 RAG 现有 `vector_store`（独立 Collection 语义已按 source 区分），不新增表。

---

## 5. 备选方案与放弃理由

| 方案 | 结论 | 理由 |
|------|------|------|
| Redis 存整会话 JSON 字符串（vs ZSET） | ❌ | 并发追加整串重写有覆盖丢失风险；ZSET 按时间戳原子追加天然有序 |
| 长期记忆存普通表（vs 向量表） | ❌ | 需求是"按语义近邻召回用户画像"；纯关系表做不到相似度召回。向量表 + 关系过滤两者都要 |
| 记忆内嵌进主对话循环（vs 独立 ContextAssembler） | ❌ | 独立组装器便于预算核算与压缩审计，避免记忆逻辑侵入编排层 |
| 历史摘要用规则截断（vs LLM 摘要） | 混合 | RAG/Tool 用规则截断（零额外调用）；会话历史摘要用 LLM（更省后续 token，一次性成本换多轮收益） |
| 引入外部记忆框架（Mem0/LangMem） | ❌ | 违反"L4 能力层不绑 API 框架"铁律；两级记忆规模自研可控 |

## 6. 对横切面的影响

- **租户隔离**：长期记忆全表 `tenant_id+user_id` 双重过滤 + API 归属校验；**记忆越权测试（A 不可读 B ）列为验收用例**
- **血缘**：`source` 记来源会话/文档；组装 usage 记各分片 token 与压缩动作（成本看板）
- **成本治理**：预算分配表为默认，超限压缩可量化（每次注明省了多少 token）
- **PII 与保留**：长期记忆含用户画像=PII → 可删除/遗忘（DELETED 软删 + 90 天物理清理）；组织记忆不含个人 PII

## 7. 验收用例（排期周末检查点）

| # | 场景 | 期望 |
|---|------|------|
| AC-1 | **越权**：用户 A/B 各有记忆，A 请求带 B 的 userId | 403 / 返回空——A 读不到 B 的记忆 |
| AC-2 | **第二场会话用长期记忆**：先写入"偏好=经济舱"→ 开新会话组装上下文 | 组装结果含该画像，未请求时不含无关画像 |
| AC-3 | **预算压缩**：构造超长历史 + 大 Top-K RAG → assemble | 总 token ≤ 预算；System/Tool 保留，历史被摘要化、RAG 降 K、记忆降 M |
| AC-4 | **短期 TTL**：写入会话 → 模拟过期 | 加载为空；未过期则正常取回 |

## 8. 实施计划（批准后）

1. `schema-memory.sql`（t_user_memory，幂等）/ Redis 存储封装（ZSET + TTL）
2. MemoryService（短/长/组织 + 越权校验）
3. 长期抽取器（LLM 抽取 `{field,value,confidence}`；高置信自动写、低置信 PENDING_CONFIRM；后台异步不阻塞）
4. ContextAssembler（预算分配 + 四级压缩级联 + usage 落审计）
5. 控制器（`/api/memory/*`、`/api/context/assemble`）+ 测试 AC-1..4

---

## 9. 变更历史

| 版本 | 日期 | 变更 | 说明 |
|------|------|------|------|
| v1.0 | 2026-09-01 | 初版 | 交用户审批（铁律：批准前不写实现） |