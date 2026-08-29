# RagController 接口契约设计

> 版本：v1.0 ｜ 状态：**已通过** (2026-08-29 审查: 实现与本文契约一致；审批后补充 citation 来源标识修复) ｜ 依据：《架构设计说明书》第 4.4.1 节 RAG 检索服务、5.2 节 RAG 双流水线、《开发排期》W3

---

## 1. 设计目的

**要解决的问题**：RagService（L4 能力层）已实现在线检索全管道（查询改写→混合检索→重排→Top-K→生成+引用），但没有对外暴露 REST 接口，导致：
1. Golden Set 评测脚本无法调用真实 RAG 链路（只能走纯 LLM 生成，测不出检索质量）
2. 智能客服前端（W3 交付物）无法发起带引用的检索问答
3. 文档入库/清空没有管理入口

**不做会怎样**：P1 收口的忠实度/召回率指标无法度量，评测门禁无法落地。

---

## 2. 接口契约（逐项说明）

### 2.1 统一前缀与租户上下文

- **前缀**：`/api/rag`（L4 能力层，供 L2 应用层及评测脚本调用）
- **租户识别**：请求头 `X-Tenant-Id`（与 AccessGateFilter 的 tenant_id 对齐），缺失时默认 `"default"`
- **统一响应体**：`Result<T>`（code/message/data），异常走 GlobalExceptionHandler

### 2.2 接口一：在线检索（核心）

```
POST /api/rag/search
X-Tenant-Id: default
Content-Type: application/json

{
  "query": "7天内商品可以无理由退货吗？",
  "topK": 5
}
```

**请求字段**：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `query` | string | 是 | 用户问题原文（不空，≤500 字符） |
| `topK` | int | 否 | 返回切片数，默认 5，范围 1-20 |

**响应字段**（`Result<RagResult>`）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `code` | int | 0=成功 |
| `message` | string | ok / 错误信息 |
| `data.answer` | string | LLM 生成的答案（带引用编号） |
| `data.citations` | string[] | 引用列表 `【1】: <source>`，source 来自入库文档文件名 |
| `data.sourceChunks` | string[] | 命中的切片内容（Top-K） |
| `data.latencyMs` | int | 全管道耗时（ms） |
| `data.confidenceScore` | double | 合成置信度 0-1（检索相似度×0.55 + 关键词覆盖×0.25 + 引用完整率×0.20） |
| `data.needsHandoff` | boolean | 是否建议转人工（前端据此点亮转人工按钮） |
| `data.handoffReason` | string | `NONE` / `NO_RETRIEVAL` / `REFUSAL` / `LOW_CONFIDENCE` |

> 上述 3 个转人工字段由 `docs/design/api/20260830-handoff-mechanism.md` 定义（P1 收口），v1.1 起加入 search 响应。

> 质量指标（faithfulness/Recall@5）**不**返回接口，由 CI 评测脚本 `scripts/eval/golden_set_runner.py` 在本地计算并产出报告 `golden-set_report.md`，指标低于基线即阻断合并。参见 `docs/design/eval/20260827-golden-set.md` §2.3。

### 2.3 接口二：文档入库

```
POST /api/rag/index
X-Tenant-Id: default
Content-Type: multipart/form-data

file=<二进制文件>   (PDF/Word/Markdown/TXT)
```

**说明**：Tika 解析 → 语义切块 → 向量化 → 写入当前租户 Collection。返回入库切片数。

| 字段 | 类型 | 说明 |
|------|------|------|
| `data` | long | 成功入库的切片数 |

### 2.4 接口三：按租户清空知识库

```
DELETE /api/rag/collections
X-Tenant-Id: default
```

**说明**：删除指定租户全部向量切片（评测重置用）。返回成功。

---

## 3. 备选方案与放弃理由

| 备选 | 放弃理由 |
|------|----------|
| 直接在 ChatController 增加 RAG 参数 | 职责混合：ChatController 是 L2 演示接口，RAG 是 L4 能力，分层铁律要求分开 |
| 评测脚本直连 VectorStore | 违背七层架构：脚本属外部调用方，必须经 L1 网关 → L4 服务，不能直连 L7 存储 |
| search 接口复用 /ask | /ask 是无状态 LLM 生成；RAG 需要检索上下文注入与引用溯源，语义不同 |

---

## 4. 合规/租户/血缘/保留策略影响

- **租户隔离**：所有接口读取 `X-Tenant-Id` 头并透传给 RagService，检索按 tenant_id 过滤（现有实现已支持）；index/delete 按租户作用域操作
- **数据血缘**：`citations` 标注来源文件名 + `sourceChunks` 命中切片，让答案可回溯到入库文档
- **PII 脱敏**：query 为业务文本，不涉及真实个人信息；接口层面不做额外脱敏（由 L1 内容安全横切面处理，P4 阶段接入）
- **保留策略**：index 入库的文档随知识库保留；delete 为显式清空操作，符合评测重置需求

---

## 5. 演进与限制

- **响应结构**：`faithfulness`/`recallAtK` 已从服务响应中移除，由 CI 评测脚本本地计算并写入 `golden-set_report.md`
- **检索深度**：当前重排为分数排序占位，Cross-Encoder 接入后接口契约不变（内部实现替换）
- **流式**：SSE 流式检索端点 `POST /api/rag/search/stream` 已于 2026-08-30 落地（P1 收口 #2），协议见 `docs/design/api/20260830-rag-stream.md`（JSON-per-line 事件流：retrieval/answer/done/error，done 含 confidenceScore/needsHandoff 等与同步接口同构的元数据）。同步 search 保留供评测使用。

---

## 6. 变更历史

| 版本 | 日期 | 变更 | 说明 |
|------|------|------|------|
| v1.0 | 2026-08-29 | 初版 | 定义 RAG 三接口契约（search/index/delete） |
