# P1 收口 #4：审计日志切面 + 客服 Web 最小界面

> 版本：v1.0 ｜ 状态：**已批准** (2026-08-30 用户审批通过，实现完成) ｜ 依据：《架构设计说明书》第 7 章质量保障（可观测性）、CLAUDE.md 铁律"所有对外调用必须透传 trace_id 并记录 Token 用量"、《开发排期》W3（审计日志切面 + 客服 Web 端最小界面）

---

## 1. 设计目的

P1 收口还剩两项 W3 交付物：

1. **审计日志切面**：现在 RAG 调用没有留痕——谁（哪个租户）、何时、问了什么、检索命中什么、延迟多少，全无从追溯。无法做 badcase 分析、无法计费、无法满足"全链路 Trace"（横切面）。
2. **客服 Web 最小界面**：SSE 流式端点已就绪，但没有消费它的界面，客服/演示场景无法使用（W3 清单项：会话 + 引用展示 + 转人工按钮）。

**不做会怎样**：P1 的"可观测性"与"可演示"两个收口缺口不闭环；后续 badcase 治理、评测平台（P3）都建立在审计留痕之上。

---

## 2. 设计细节

### 2.1 审计日志切面

**方式**：`@Aspect` 切 `RagService.search()` 与 `RagService.streamSearch()`，在方法执行后记一条**结构化 JSON 日志**（SLF4J + Logback），经现有 MDC 自动带 `trace_id`。

**审计字段**：

| 字段 | 来源 | 说明 |
|------|------|------|
| `trace_id` | MDC | 全链路 Trace（CLAUDE.md 铁律） |
| `ts` | 当前时间 | 何时 |
| `tenant_id` | 入参 | 谁 |
| `query` | 入参 | 问了什么（截断至 500 字符） |
| `topK` | 入参 | 检索参数 |
| `chunkCount` | 结果 | 检索命中切片数 |
| `sources` | 结果 | 命中文档（去重，至多 5 个） |
| `latencyMs` | 结果 | 全管道耗时 |
| `firstTokenMs` | 结果（仅流式） | 首 Token 延迟 |
| `needsHandoff` / `handoffReason` | 结果 | 转人工判定 |
| `confidenceScore` | 结果 | 合成置信度 |
| `answerLen` | 结果 | 答案字符数（**Token 用量代理**） |

> **Token 用量说明**：真实 token 计数属于 L6 模型服务层职责（CLAUDE.md 铁律"记录 Token 成本"），当前 `LlmGateway` 未暴露 usage。P1 审计先用 `answerLen` 作代理；L6 计量拦截器在 P2 接入后，审计字段追加 `inputTokens/outputTokens`。**P2 起不硬编码**。

**落库**：P1 只写结构化日志文件（`logs/audit.log`），**不建审计表**。P2 评测平台引入 `t_audit` 表（按租户隔离、保留策略）时另行设计（走表设计模板）。

### 2.2 客服 Web 最小界面

**位置**：`src/main/resources/static/chat/`（`index.html` + `app.js` + `style.css`），由同一 Spring Boot 应用同源服务（`/chat/`），**无跨域、无前端构建**。

**功能**：

| 功能 | 实现 |
|------|------|
| 会话输入 + 消息列表 | 顶部输入框，Enter 发送，消息追加列表 |
| **打字机效果** | `fetch('/api/rag/search/stream', {method:'POST', body:JSON})` + `response.body` ReadableStream 逐行读 `data:` 块，JSON 解析按 `type` 分发：`retrieval` 显示"命中 N 份资料"、`answer` 逐 token 追加、`done` 收尾、`error` 红字 |
| **引用展示** | `done.citations` 渲染为 `【1】: 文件名` 列表，点击可看对应 `sourceChunks` 摘要 |
| **转人工按钮** | `done.needsHandoff=true` 时点亮"转人工"按钮（含原因 reason），点击提示"已为您转人工"（P1 仅提示，人工坐席 P2） |
| 置信度/延迟 | `done.confidenceScore`、`latencyMs`、`firstTokenMs` 展示在消息元数据行 |
| 租户 | 固定 `X-Tenant-Id: default`（P1 演示；多租户登录在 P2 客服系统） |

**样式**：最小可用——居中卡片、消息气泡、流式光标，无框架依赖。

---

## 3. 备选方案与放弃理由

| 方案 | 结论 | 理由 |
|------|------|------|
| 审计落 MySQL 表（P1 就建 t_audit） | ❌ 放弃 | P1 无审计查询/计费消费方，建表拍脑袋（字段会变）；日志先行、P2 随评测平台定表结构，符合"设计先行"节奏 |
| 审计切 RAGController 而非 RagService | ❌ 放弃 | Controller 层拿不到完整的检索/转人工结果（流式结果在 Flux 里），切 Service 才有完整字段 |
| 客服界面用 React/Vue 工程 | ❌ 放弃 | 需引入前端构建、部署两套；P1 最小界面用静态 HTML/JS 同源服务，零依赖，够演示 |
| 转人工落库接口（POST /api/rag/handoff） | ❌ 放弃 | 同转人工机制设计（20260830-handoff-mechanism.md §2.3）：无人工坐席消费前是空转，P2 建表时一起设计 |

---

## 4. 合规/租户/血缘/保留策略影响

- **租户隔离**：审计日志按 `tenant_id` 记录（不混租户），P1 只写文件不落库、无跨租户读；客服界面固定 default 租户演示。
- **PII 脱敏**：`query`/`answer` 为业务文本，含用户输入——审计日志会留存原始 query，属日志敏感面。P1 日志文件限本机/CI artifact 不对外；P2 审计表设计时定义脱敏（query 截断、敏感词打码）与保留策略（对标"会话原文 180 天"）。
- **数据血缘**：审计记录 `sources` 命中文档 + `answer`，badcase 可回溯"问了什么→检索到哪些→答了什么→是否转人工"。
- **保留策略**：日志按 Logback 滚动（按天/大小），P2 表设计时定正式保留期。

---

## 5. 演进与限制

- **真实 token 计量**：P1 用 `answerLen` 代理；L6 计量拦截器（CLAUDE.md 铁律）在 P2 落地后审计追加真实 input/output token。
- **审计查询界面**：P1 只有日志文件，无检索 UI；P2 评测平台/管理台提供。
- **客服界面功能边界**：转人工按钮 P1 仅提示，人工坐席流转（工单、状态）P2 建表实现；多租户登录 P2。

---

## 6. 实施清单（文档通过后）

1. `AuditLogAspect`：`@Around` RagService.search/streamSearch，环绕计时 + 结果字段组装 → JSON 日志；`@Aspect @Component`，`@ConditionalOnProperty(app.audit.enabled=true)` 可关
2. `application.yml`：`app.audit.enabled=true` + Logback 单独 audit logger（`logs/audit.log`）
3. 静态页：`static/chat/index.html` + `app.js` + `style.css`（SSE 消费 + 打字机 + 引用 + 转人工按钮）
4. 实测：起服访问 `/chat/`，问答一条看打字机/引用/转人工按钮；`logs/audit.log` 出现审计 JSON 行
5. 回写 CHANGELOG

---

## 7. 变更历史

| 版本 | 日期 | 变更 | 说明 |
|------|------|------|------|
| v1.0 | 2026-08-30 | 初版 | 定义审计切面字段与日志方案、客服静态页功能清单 |
