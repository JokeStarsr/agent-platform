# RAG 在线检索 SSE 流式端点设计

> 版本：v1.0 ｜ 状态：**已批准** (2026-08-30 用户审批通过，实现完成，首 Token 实测 ≤2s 待最终确认) ｜ 依据：《架构设计说明书》5.2 节 RAG 双流水线、P1 闸门（首 Token ≤ 2s）、《开发排期》W3；扩展 `docs/design/api/20260829-rag-controller.md`（其 §5 已预留"SSE 流式检索以独立 stream 端点补充"）

---

## 1. 设计目的

**要解决的问题**：`/api/rag/search` 是同步响应，客户端要等 LLM 生成完整个答案才能看到第一个字。两个硬需求因此无法满足：

1. **P1 闸门"首 Token ≤ 2s"**：同步接口测不到首 Token，且交互体感差（一次回答 3-5s 全程白屏）。
2. **W3 客服 Web 端打字机效果**：需要边生成边逐字推送，引用于答案流式到达后一并展示。

**不做会怎样**：首 Token 闸门无法度量，客服界面无法实现"流式打字 + 引用即时展示"，P1 收口不闭环。

---

## 2. 接口契约

### 2.1 端点与请求

```
POST /api/rag/search/stream
X-Tenant-Id: default
Content-Type: application/json
Accept: text/event-stream

{ "query": "7天内如何退货？", "topK": 5 }
```

- 与 `/api/rag/search` 共用请求体 `SearchRequest`（query/topK，校验一致）
- 响应 `Content-Type: text/event-stream`，SSE 长连接
- **原 `/api/rag/search` 保留**（评测脚本依赖完整 answer 同步返回；stream 为增量补充）

### 2.2 SSE 协议（每行一个紧凑 JSON）

沿用现有 `Flux<String>` + `text/event-stream` 模式（`/api/chat/stream` 已验证：Spring MVC 把每个元素写成 `data:<元素>\n\n`），**每个元素是一行无换行的紧凑 JSON**，前端按 `type` 字段分发。不用 `event:` 帧名（纯 MVC 下 ServerSentEvent 帧名序列化无保证，JSON-per-line 与已验证模式一致）。

```
data:{"type":"retrieval","chunkCount":5,"topK":5,"sources":["tc_policy_v3.md","tc_payment_v1.md"]}
data:{"type":"answer","text":"根据"}
data:{"type":"answer","text":"知识库内容"}
...（每个 token 一行 answer）
data:{"type":"done","answer":"<完整答案>","citations":["【1】: tc_policy_v3.md",...],"sourceChunks":[...],"confidenceScore":0.71,"needsHandoff":false,"handoffReason":"NONE","latencyMs":3450,"firstTokenMs":820}
```

**事件类型逐项说明**：

| type | 时机 | 字段 | 说明 |
|------|------|------|------|
| `retrieval` | 检索完成、生成开始前 | `chunkCount` 命中切片数；`topK`；`sources` 命中文档名去重列表 | 让前端先展示"正在检索到哪些资料"；也提供检索耗时观测点 |
| `answer` | 生成过程中 | `text` 单 token 文本 | 每个 token 一行，前端按序拼接实现打字机效果 |
| `done` | 生成完成 | `answer` 完整答案；`citations`；`sourceChunks`；`confidenceScore`；`needsHandoff`；`handoffReason`；`latencyMs` 全管道耗时；`firstTokenMs` 首 Token 耗时 | **与 `/api/rag/search` 响应结构一致**，前端可直接复用渲染逻辑 |
| `error` | 检索异常或生成中断 | `message` 错误信息 | SSE 已开启后无法改状态码，用 error 事件兜底 |

> **关键设计**：`confidenceScore`/`needsHandoff`/`handoffReason` 只能在 `done` 事件计算——合成置信度依赖**完整答案**（引用正则、拒答信号都要全文）。与同步端点复用同一 `computeHandoff` 逻辑，保证两接口判定一致。

### 2.3 首 Token 指标

- `firstTokenMs` = 请求进入控制器 → 首个 `answer` 事件发出的耗时，作为 P1 闸门"首 Token ≤ 2s"的度量。
- 生成使用 `LlmGateway.stream(system, user)`（已实现，Flux<String> 逐 token）；检索为同步快速阶段（毫秒级），生成首 token 由 LLM 决定。
- 实测工具：新增 `scripts/eval/measure_first_token.py`，对 Golden Set 抽样 N 条调 stream 端点，取首个 answer 事件的到达时间，报告平均/分位（P95）。不纳入 golden_set_runner.py（那是质量评测，latency 单独测避免互相干扰）。

---

## 3. 备选方案与放弃理由

| 方案 | 结论 | 理由 |
|------|------|------|
| 复用 /search 返回 `Flux<...>` 但不加事件 | ❌ 放弃 | 语义混乱：同步接口的 Result<T> 结构与流式不兼容，且无法表达"检索元数据先到、答案分片后到、最后附元数据"的时序 |
| 用 `ServerSentEvent` 帧名（`event: answer` 等） | ❌ 放弃 | 纯 Spring MVC（无 WebFlux codec）下帧名序列化无保证；现有 /api/chat/stream 用 Flux<String> 已过验证，JSON-per-line 与其同构、风险最低 |
| 生成完成前不发任何事件（先攒完整再一次性发） | ❌ 放弃 | 首 Token 闸门测不到，打字机效果无意义，与设计目的矛盾 |
| 前端轮询 /search 模拟流式 | ❌ 放弃 | 非真流式，延迟叠加，无法满足首 Token 指标；违背"SSE 流式检索"设计意图 |

---

## 4. 合规/租户/血缘/保留策略影响

- **租户隔离**：读取 `X-Tenant-Id` 头透传 RagService，检索按 tenant_id 过滤（与 /search 完全一致）；无新增存储，无跨租户风险。
- **数据血缘**：`done` 事件的 `citations`/`sourceChunks` 与同步接口同构，答案可回溯来源文档，血缘链路不因流式中断。
- **PII 脱敏**：流式推的是检索结果与生成文本，同同步接口，不新增敏感数据面。
- **保留策略**：SSE 是瞬时传输不落库，无保留策略变更；客户端中断即取消上游流式调用（Flux 订阅取消 → Spring AI 取消 LLM 请求），不浪费 token。

---

## 5. 演进与限制

- **首 Token 依赖上游**：DeepSeek 首 token 一般 <1-2s，但遇上游限流/拥塞会超 2s——本端点提供度量手段，达标与否取决于上游与负载（与评测 LLM-as-judge 抖动同类，属环境因素）。
- **无重连机制**：SSE 客户端断线即结束，前端需自行处理"断线重问"；P2 客服界面按需补充 `Last-Event-ID`/重连语义。
- **token 分片粒度**：当前按 `LlmGateway.stream().content()` 的原始 chunk 粒度推（约一个词/标点）；如需更细粒度可在 P2 做前端合字。

---

## 6. 实施清单（文档通过后）

1. `RagService.streamSearch(query, topK, tenantId)` 返回 `Flux<String>`：检索（复用 search 的改写/混合检索/重排/Top-K）→ 发 retrieval 事件 → `llmGateway.stream()` 逐 token 发 answer 事件并记 firstTokenMs → 累积全文 → done 事件（复用 computeHandoff 计算 confidence/needsHandoff/handoffReason）
2. `RagController` 新增 `POST /search/stream`（produces = text/event-stream）
3. Jackson `ObjectMapper` 序列化事件 JSON（非 ASCII 转 \uXXXX，保证 data 行纯 ASCII 无换行）
4. 错误处理：检索异常发 error 事件；生成流 doOnError 发 error 事件
5. 实测：`scripts/eval/measure_first_token.py` 抽样 N 条报告首 Token 分位，确认 ≤ 2s
6. 回写 `docs/design/api/20260829-rag-controller.md` §5 标注流式端点已落地

---

## 7. 变更历史

| 版本 | 日期 | 变更 | 说明 |
|------|------|------|------|
| v1.0 | 2026-08-30 | 初版 | 定义 /api/rag/search/stream SSE 协议（JSON-per-line 事件流）、首 Token 指标、实施清单 |
