# 管理台 6 入口落地：三个只读列表/统计接口 + 五个静态管理页

> 版本：v1.0 ｜ 状态：**已批准** (2026-09-02 计划审批通过) ｜ 依据：用户反馈"首页6入口点击不可用"、《架构设计说明书》七层架构、《开发排期》各层演示项

---

## 1. 设计目的

首页（`static/index.html`）6 个入口当前直接把浏览器导向 REST API 裸链接，体验断裂。实测结果：

| 入口 | 现状 | 根因 |
|------|------|------|
| 客服对话 `/chat/` | HTTP 404 | Spring Boot 对静态目录 URL（`/chat/`）不做 index.html 兜底，`/chat/index.html` 单独访问正常 |
| Workflow `/api/workflow/flows` | 返回裸 JSON | 是 GET 接口但浏览器直接显示 JSON，非页面 |
| 工具协议层 `/api/agent/runs` | `code:500 系统繁忙` | Controller 根路径只有 `@PostMapping`，GET 被全局异常兜底 |
| 记忆 `/api/memory/demo` | 返回裸 JSON | 同上 |
| RAG `/api/rag/search` | `code:500 系统繁忙` | 只有 `@PostMapping` |
| 知识库 `/api/rag/collections` | `code:500 系统繁忙` | 只有 `@DeleteMapping` |

**要解决的问题**：6 个入口各自对应一个真实可浏览的管理 UI 页面；为 3 个缺失只读能力的资源补齐 GET 接口（Agent 运行列表、Workflow 实例列表、RAG 集合状态）；统一分页结构。

**不做会怎样**：平台仍无管理台观感，所有层能力只能透过裸 JSON 查看，无法演示、无法核查。

---

## 2. 设计细节

### 2.1 统一分页 `PageResult<T>`

新增 `com.agent.common.PageResult<T>`，字段 `page / size / total / totalPages / items`，工厂 `PageResult.of(page, size, total, items)`。

- `page` 从 1 起；`size` 默认 20，非法值在 Repository 内 clamp（不引 `@Validated`，与现有 Controller 风格一致）。
- `totalPages = ceil(total / size)`，`total=0` 时 `totalPages=0`。

### 2.2 GET /api/agent/runs —— Agent 运行列表

`AgentRunController` 新增无路径 `@GetMapping`（与既有 `GET /{runId}` 不冲突）。

**参数**：`page`(默认1)、`size`(默认20)、`status`(选填，如 RUNNING/COMPLETED/FAILED/TIMEOUT/CANCELED/WAITING_APPROVAL)。

**返回 items 字段**：`runId / appId / task / status / maxSteps / stepsDone / tokensUsed / createdAt / finishedAt`。

**链路**：`AgentRuntimeService.listRuns(tenantId, page, size, status)` → `AgentRunRepository.countRuns + pageRuns`：

```sql
SELECT ... FROM t_agent_run WHERE tenant_id = ? [AND status = ?] ORDER BY created_at DESC LIMIT ? OFFSET ?
```

走既有索引 `idx_agent_run_tenant_created`。

### 2.3 GET /api/workflow/instances —— 工作流实例列表

`WorkflowController` 新增 `@GetMapping`（与既有 `GET /instances/{instanceId}` 不冲突）。

**参数**：同上 `page / size / status`。

**返回 items 字段**：`instanceId / appId / flowId / status / errorMsg / createdAt / finishedAt`。

**链路**：`WorkflowService.listInstances(...)` → `t_workflow_instance`，走 `idx_wf_inst_tenant`。

### 2.4 GET /api/rag/collections —— 知识库集合状态

`RagController` 新增 `@GetMapping`（与既有 `DELETE /collections` 共存，方法签名区分）。

**租户隔离机制已确认**：RAG 不分物理 collection，而是**同一 pgvector 表 `vector_store` + document metadata 写 `tenant_id` 字段 + 检索时 `FilterExpression(name_eq("tenant_id", tenantId))` 过滤**。故"集合状态"= 按租户聚合的向量数据统计。

**新增 L7 仓库** `com.agent.data.rag.RagCollectionRepository`（JdbcTemplate 直查，只读）：

```sql
SELECT count(*)                                    AS chunk_count,
       count(DISTINCT metadata->>'source')         AS doc_count,
       max((metadata->>'indexed_at')::bigint)      AS last_update_ms
FROM vector_store
WHERE metadata->>'tenant_id' = ?
```

**返回**：`{ collectionName: "vector_store", tenantId, chunkCount, docCount, lastUpdateMs }`。

**配套**：`RagService.indexDocument` 切块时 metadata 新增 `indexed_at`（当前 epoch ms），存量切片无此字段→`lastUpdateMs` 为 null（前端空态）。

### 2.5 静态目录 URL 修复

新增 `com.agent.common.StaticViewRedirectConfig`（`implements WebMvcConfigurer`），`addViewController` 注册 6 条精确映射：

```
/chat/        → redirect:/chat/index.html
/workflow/    → redirect:/workflow/index.html
/agent/       → redirect:/agent/index.html
/memory/      → redirect:/memory/index.html
/rag/         → redirect:/rag/index.html
/collections/ → redirect:/collections/index.html
```

精确路径优先于默认 `/**` 静态映射（Spring MVC 特异性匹配），两个入口（目录根 URL 与 index.html 直链）均可用。

### 2.6 前端：5 个静态管理页

**位置**：`static/{workflow,agent,memory,rag,collections}/index.html + app.js`，共享 `static/common.css`（chat 页不改）。

**统一约定**：原生 JS + fetch；所有请求带 `X-Tenant-Id: default`；校验 `data.code === 0` 后渲染，非 0 展示 `data.message`；风格对齐 chat 页（蓝色 `#2563eb`、背景 `#f2f4f8`、720px 居中、圆角卡片、状态徽章）。

| 页面 | 调用接口 | 交互 |
|------|---------|------|
| `/workflow/` | GET /flows、/flows/{name}、/instances、/instances/{id}/nodes | 内置流程卡片 + 定义预览；实例表格（分页/状态筛选，status 徽章） |
| `/agent/` | GET /runs、/runs/{runId}、/runs/{runId}/retry、DELETE /runs/{runId} | 运行表格 + 详情面板 + 取消/重试按钮 |
| `/memory/` | GET /demo、/short?sessionId=、/user/{userId}、/pending?userId=、POST /context/assemble | 表单查询三级记忆，上下文组装演示 |
| `/rag/` | POST /search | 检索表单 + 答案/引用/置信度/转人工结果卡片 |
| `/collections/` | GET /collections、POST /index、DELETE /collections | 统计卡片 + 文档上传入库 + 清空（确认弹窗） |

### 2.7 首页改动

6 卡片 href：`/chat/` 不变；`/api/workflow/flows`→`/workflow/`；`/api/agent/runs`→`/agent/`；`/api/memory/demo`→`/memory/`；`/api/rag/search`→`/rag/`；`/api/rag/collections`→`/collections/`。

---

## 3. 备选方案与放弃理由

| 方案 | 结论 | 理由 |
|------|------|------|
| 集合统计用 `pgvector count()` 全表（不带租户过滤） | ❌ 放弃 | 跨租户泄漏，违反租户隔离铁律 |
| 给 RAG 改分物理 collection（每租户一集合） | ❌ 放弃 | 动既有检索/入库全链路，成本高；现 metadata 打标机制已成立，统计走聚合即可 |
| 各页面独立 CSS | ❌ 放弃 | 5 个页面重复样式，抽 `common.css` 一处维护 |
| 列表接口引 Spring Data 分页（Pageable） | ❌ 放弃 | 项目 Repository 为手写 JdbcTemplate 风格，引入 JPA 分页依赖与既有代码不一致 |
| 首页 href 直改 index.html 而非加 redirect | ❌ 放弃 | 目录根 URL 通用且更可读，`/chat/` 需保持对既有链接兼容；addViewController 6 行成本可忽略 |

---

## 4. 合规/租户/血缘/保留策略影响

- **租户隔离**：3 个 GET 接口全部读 `X-Tenant-Id`（缺省 default）；列表 SQL 的 `WHERE tenant_id = ?` 强制租户范围；集合统计聚合 `metadata->>'tenant_id' = ?`，不混租户。
- **数据治理**：无新表（复用 `t_agent_run`、`t_workflow_instance`、`vector_store`）；`indexed_at` 仅新增切片元数据字段，存量数据 null 可容忍；3 个接口均为只读，不涉及写操作幂等键（铁律范围外）。
- **血缘**：列表/统计展示 `runId / instanceId / sources`，可从入口回溯到执行记录与文档切片。
- **保留策略**：不新增存储，无新保留策略；页面数据随时间自然滚动。
- **安全**：管理页当前无鉴权（与首页同源，P1 演示；鉴权随平台统一登录 P2 引入，届时静态目录加拦截）。

---

## 5. 演进预判

- 分页统一 `PageResult<T>` 后，后续所有列表类接口（工具调用清单、token 用量报表等）沿用同一结构。
- 管理页面从"只读演示"演进为"操作台"（审批、重试、上传已在页面内），需鉴权+审计时在 P2 统一门户下收口。
- 集合统计粒度将来可升级为按文档/目录的桶聚合（`source` 前缀），接口字段向后兼容增加。

---

## 6. 实施清单

- [x] 本设计文档（用户审批）
- [x] 后端：`PageResult` → `RagCollectionRepository` → `AgentRunRepository`/`WorkflowRepository` 分页 → Service 接口/实现 → 3 个 Controller GET
- [x] 契约测试：`AgentRunControllerContractTest`（新）、`WorkflowControllerContractTest`（新）、`RagControllerContractTest`（改，加 collections）
- [x] 前端：`common.css` + 5 页 index.html/app.js
- [x] `StaticViewRedirectConfig` + 首页 6 href
- [x] 起服（8085 临时实例）6 页面逐一实测定点验收（3 接口真实数据、indexed_at 聚合验证；RAG 检索/上传因验证实例无真实 LLM key 留待正式实例重启验证）

## 7. 变更历史

| 日期 | 变更 | 状态 |
|------|------|------|
| 2026-09-02 | 初稿，计划审批通过 | 已批准 |
| 2026-09-02 | 实现完成（后端/契约测试/前端/静态重定向/首页），8085 实例实测通过 | 已实现 |