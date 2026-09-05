# agent-platform 使用说明书

> 版本：v1.0 ｜ 更新：2026-09-05 ｜ 适用：W12 完成后（MCP 网关 + 工具市场 + 执行沙箱已上线）
> 服务地址：`http://localhost:8082`（本地启动方式见 §6.1）

---

## 1. 这套系统是什么

**一句话**：一个企业级智能体平台——把"大模型 + 知识库 + 工具 + 流程编排"组装成可运营的 AI 应用，并保证**安全（沙箱/鉴权）、可控（人工审批/转人工）、可评估（Golden Set 门禁）**。

它不是聊天机器人 Demo，而是一个**平台**：你通过"配置"而非"改代码"接入新业务场景，通过管理台观测和治理每一次 AI 行为。

### 全景图

```
┌────────────────────────── 浏览器管理台（8 个页面）──────────────────────────┐
│  /chat/ 客服对话   /rag/ 检索   /collections/ 知识库   /workflow/ 流程      │
│  /agent/ 运行监控  /memory/ 记忆  /apps/ 应用工厂                          │
│  /mcp-gateway/ 工具网关   /tool-market/ 工具市场                            │
└──────────────────────────────────┬─────────────────────────────────────────┘
                                   │ REST / SSE（租户头 X-Tenant-Id）
┌──────────────────────────────────▼─────────────────────────────────────────┐
│ L1 接入层：AccessGateFilter（租户解析）· McpAuthFilter（MCP 鉴权）          │
│ L2 应用层：客服对话 / 应用工厂消费点                                         │
│ L3 编排层：Agent Runtime（ReAct 循环）· Workflow 引擎（DAG+人工节点）        │
│            应用工厂（AppRegistry）                                          │
│ L4 能力层：RAG（检索/引用/转人工）· 记忆（三级）· 上下文组装（16K 预算）     │
│ L5 工具层：ToolEngine（幂等/校验/超时）· MCP 网关（双向）· 工具市场          │
│            执行沙箱（Docker 代码沙箱 + SQL 只读通道）                       │
│ L6 模型层：LlmGateway（统一出模）· Token 计量拦截器                          │
│ L7 数据层：PG + pgvector（向量）· Redis（短期记忆）· 各业务表               │
└────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. 设计思想（为什么长这样）

| 思想 | 一句话解释 | 在系统里的体现 |
|------|-----------|---------------|
| **应用 = 配置** | 新场景接入不改代码，只填 8 项配置 JSON | 应用工厂（W9）：角色/Prompt/知识库/工具/记忆/评测/转人工/配额 |
| **写操作必须人工确认（HITL）** | AI 可以查，花钱/动数据必须人点头 | book_order 先挂起等审批；pay_order 直接硬 403（双人复核前不放行） |
| **写操作必须幂等键** | 网络重试/循环重试不会重复下单 | ToolEngine 强制 `idempotencyKey`，同键重放返回首次结果 |
| **答案必须可溯源** | 每句回答标注来自哪份文档 | RAG 引用编号【1】【2】，citation 可回看原文切片 |
| **不确定就转人工** | 低置信度/敏感意图不硬答 | 置信度门控 0.25 + REFUSAL/SENSITIVE 强制转人工 |
| **一切皆审计** | 谁、何时、调了什么、花了多少 token | logs/audit.log + logs/metering.log 双日志 |
| **安全是红线** | 非信任代码/SQL 不进主进程 | Docker 沙箱（无网络/限额/强杀）+ SQL 只读通道 + 逃逸测试 100% |
| **指标不达标不上线** | 评测集进 CI，坏了自动拦 | Golden Set 92 条门禁：忠实度/召回/转人工率 |

---

## 3. 系统原理（一次请求的生命周期）

以"用户问客服：退货运费谁出？"为例：

```
1. 浏览器 POST /api/rag/search/stream {query, appId}
        ↓ L1 AccessGateFilter 解析 X-Tenant-Id → 写入 MDC（trace_id 贯穿全程）
2. L2 应用层查 AppRegistry：appId=cs_customer_service 的 8 项配置
        ↓ 取出：系统 Prompt / 工具白名单 / 转人工阈值 0.25 / 配额
3. L4 RAG 管道：查询改写 → pgvector 向量检索 Top-K → 重排 → 组装上下文
        ↓ ContextAssembler 按 16K 预算分配：System 10% / RAG 35% / 历史 25% / 记忆 15% / 工具 15%
4. L6 LlmGateway 流式生成（MeteredChatModel 计量 token → metering.log）
        ↓ SSE 逐 token 推给浏览器（打字机效果）
5. 生成完毕：合成置信度 = 检索相似度×0.55 + 关键词覆盖×0.25 + 引用完整率×0.20
        ↓ 低于 0.25 或命中拒答/敏感词 → needsHandoff=true（前端点亮转人工按钮）
6. 审计切面写 audit.log：tenant/query/chunkCount/latency/needsHandoff/token 代理
```

**商旅下单（含人工审批）的生命周期**：

```
POST /api/workflow/instances {flowRef: trip_booking}
  → n1 policy_query（查政策，READ 直接跑）
  → n2a/n2b 并行比价（PARALLEL）
  → n2c policy_check（违规 → 流程 FAILED + POLICY_VIOLATION，100% 拦截）
  → n3 人工确认节点（HUMAN）→ 流程挂起，DB 落库（重启不丢）
      → 你在 /workflow/ 页面点"同意" → POST /approval
  → n4 book_order（WRITE，幂等键落 t_tool_invocation）
  → n5 notify_user → COMPLETED
超时 30 分钟未审批 → 升级扫描器标记 escalated（仍可补审批）
```

---

## 4. 四大使用场景

| 场景 | 入口 | 你做什么 | 系统做什么 |
|------|------|----------|-----------|
| **智能客服**（RAG 问答） | `/chat/` | 上传业务文档、提问 | 检索→引用回答→低置信转人工 |
| **商旅助手**（流程编排） | `/workflow/` | 提交差旅任务、审批 | 查政策→比价→合规校验→人工确认→下单→通知 |
| **工具开放**（MCP 生态） | `/mcp-gateway/` | 管理租户授权、看调用统计 | 把平台工具以 MCP 标准协议暴露给外部 Agent |
| **工具自助上架** | `/tool-market/` | 注册新工具→试调→发布 | 校验 Schema→跑测试用例→进执行引擎→可被调用 |

附加能力：**执行沙箱**（`/api/sandbox/code` 跑非信任代码、`/api/sandbox/sql` 跑只读查询）、**记忆服务**（`/memory/` 演示三级记忆）。

---

## 5. 5 分钟上手（最快路径）

### 第一步：确认服务在跑

```
浏览器打开 http://localhost:8082/  → 应看到首页 8 张卡片
curl http://localhost:8082/api/mcp/health → {"status":"UP","toolCount":11,...}
```

### 第二步：问客服一个问题（体验 RAG + 引用 + 转人工）

1. 首页点 **💬 客服对话** → `/chat/`
2. 输入：`退货的运费由谁承担？` → 回车
3. 观察三件事：
   - 答案带【1】【2】引用编号 → 点击可看来源文档切片
   - 底部元数据：置信度分数、首 Token 延迟
   - 若问刁钻问题（如`你们的CEO是谁`）→ **转人工按钮点亮**（REFUSAL 信号）

### 第三步：跑一个商旅流程（体验 HITL 审批）

1. 首页点 **🔁 Workflow 引擎** → `/workflow/`
2. 选流程 `trip_booking`，输入任务（如 `北京出差3天，标准方案`）→ 提交
3. 流程跑到**人工确认节点**会挂起（状态 WAITING_APPROVAL）
4. 点该节点 → **同意** → 流程继续：下单 → 通知 → COMPLETED
5. 若把任务改成头等舱（违规）→ 流程直接 FAILED + POLICY_VIOLATION

### 第四步：上架一个新工具（体验自助注册）

1. 首页点 **🛒 工具市场** → `/tool-market/`
2. 填注册表单：
   - toolName: `calendar_create_event`
   - 描述：`创建日程提醒事件，支持指定时间与参与人（演示工具，注册即用）`
   - parameters: `{"type":"object","properties":{"title":{"type":"string"}},"required":["title"]}`
   - testcases: `[{"name":"正常创建","arguments":{"title":"周会"},"expectCode":0}]`
3. 点注册 → 列表出现 DRAFT 行 → 点**试调**（看返回）→ 点**发布**
4. 发布后该工具进 ToolEngine，MCP `tools/list` 也能看到它（≤30 分钟闸门实测远快于此）

### 第五步：看沙箱（体验安全边界）

```
curl -X POST http://localhost:8082/api/sandbox/code \
  -H "Content-Type: application/json" -H "X-Tenant-Id: default" \
  -d '{"language":"python","code":"print(2+3)"}'
# → {"exitCode":0,"stdout":"5\n",...}   容器里跑的，无网络/限额/用完即焚

curl -X POST http://localhost:8082/api/sandbox/sql \
  -H "Content-Type: application/json" -H "X-Tenant-Id: default" \
  -d '{"sql":"SELECT app_id FROM t_app LIMIT 2"}'
# → 返回数据；换成 "DROP TABLE t_app" → 403 被拒
```

---

## 6. 操作手册（逐页面）

### 6.1 启动与停止

```powershell
# 启动（PowerShell，引号必须带）
cd D:\ClaudeCode\AgentProduct\agent-platform
$env:LLM_API_KEY=$env:ANTHROPIC_API_KEY
$env:LLM_BASE_URL="http://localhost:8180"
mvn spring-boot:run "-Dspring-boot.run.arguments=--server.port=8082"

# 前置依赖（docker 已起则跳过）
docker start agent-platform-pg    # pgvector 数据库

# 停止：Ctrl+C 或杀 java 进程
```

### 6.2 首页 `/`
8 张卡片 = 8 个入口，每张卡右上角标注所属周（W9/W10/W11...）。底部状态条显示端口与租户头要求。

### 6.3 客服对话 `/chat/`
- **输入框**：提问；回答流式打字机
- **引用**：done 事件的 citations 可展开看 sourceChunks 原文
- **转人工**：needsHandoff=true 时按钮点亮，显示 handoffReason（REFUSAL/SENSITIVE/LOW_CONFIDENCE/NO_RETRIEVAL）
- **元数据**：confidenceScore（0~1）、latencyMs、firstTokenMs

### 6.4 RAG 检索 `/rag/`
- 输入查询 → 返回命中切片 + 分数 + 完整答案
- 用于调试验证：知识库有没有收录、检索命中是否正确

### 6.5 知识库 `/collections/`
- **上传**：选择 md/txt/pdf → POST /api/rag/index（按 `##` 章节切块入库）
- **清空**：按租户清空向量库（危险操作，评测前重置用）
- **统计**：各文档切片数、最近索引时间

### 6.6 Workflow `/workflow/`
- **提交实例**：选 flowRef（trip_booking）或贴 JSON 定义 → input
- **实例列表**：状态筛选（RUNNING/WAITING_APPROVAL/COMPLETED/FAILED）
- **节点视图**：每个节点状态/变量/补偿标记
- **审批**：WAITING_APPROVAL 节点 → 同意/驳回（驳回触发补偿回滚）
- **重试**：FAILED 节点 → retry 新建 attempt
- **SSE**：实例详情可开实时事件流

### 6.7 Agent 运行 `/agent/`
- **提交任务**：task 文本 → Agent Runtime ReAct 循环（规划→执行→观察）
- **监控**：每步 trace（选了什么工具/参数/结果）、护栏触发记录（MAX_STEPS/TIMEOUT）
- **审批**：写操作暂停等确认（同 HITL）

### 6.8 记忆 `/memory/`
- **短期**：写 session 消息（Redis TTL 30min）
- **长期**：写用户画像（低置信度进待确认队列）
- **上下文组装**：看 16K 预算如何分配与压缩

### 6.9 应用工厂 `/apps/`
- **新建**：appId + 8 项配置 JSON（缺项会被校验器拦截）
- **启停**：CREATED → start → ENABLED → suspend → SUSPENDED
- **配置**：点"配置"看完整 JSON；修改配置版本 +1
- 消费方：/chat/ 已带 `appId=cs_customer_service`；新应用创建后即可被 API 消费

### 6.10 MCP 网关 `/mcp-gateway/`
- **健康**：UP/DEGRADED、工具数、uptime
- **工具列表**：当前注册的全部工具（含权限 READ/WRITE/PAYMENT）
- **授权管理**：查某租户的授权列表；授权/撤销某工具（deny-by-default）
- **端点信息**：SSE 地址 `/mcp/sse`，鉴权头 `X-Tenant-Id + X-Api-Key`
- **外部接入**：任何标准 MCP Client 连 `/mcp/sse` → initialize → tools/list → tools/call

### 6.11 工具市场 `/tool-market/`
- **目录**：全部工具（含版本、来源 BUILTIN/SELF_REGISTERED、状态）
- **注册→发布闭环**：填表 → DRAFT → 试调 → 发布（测试用例 smoke 全绿）→ PUBLISHED
- **下架**：从执行引擎移除但保留历史版本
- **发新版本**：复制为 v+1 草稿，再次发布才上线

### 6.12 沙箱（暂无页面，API 直调）
见 §5 第五步。健康检查 `GET /api/sandbox/health`。

---

## 7. API 速查表

| 方法 | 路径 | 用途 |
|------|------|------|
| POST | `/api/rag/search` | RAG 检索问答（同步） |
| POST | `/api/rag/search/stream` | RAG SSE 流式 |
| POST | `/api/rag/index` | 文档入库（multipart） |
| DELETE | `/api/rag/collections` | 清空租户知识库 |
| POST | `/api/workflow/instances` | 提交流程 |
| POST | `/api/workflow/instances/{id}/approval` | 审批 |
| POST | `/api/agent/runs` | 提交 Agent 任务 |
| POST | `/api/chat/ask` | 简单问答（可带 model 字段切模型） |
| GET | `/api/mcp/health` · `/api/mcp/tools` | MCP 网关状态/工具 |
| GET/POST | `/api/mcp/grants` | 查询/授权租户工具 |
| GET/POST | `/api/tool-market` | 工具目录/注册 |
| POST | `/api/tool-market/{id}/publish` | 发布工具 |
| POST | `/api/sandbox/code` · `/api/sandbox/sql` | 沙箱执行 |
| GET | `/api/apps` | 应用列表 |

> 所有请求带 `X-Tenant-Id` 头（默认 `default`）。MCP 端点另需 `X-Api-Key`（本地 dev-key）。

---

## 8. 常见问题（FAQ）

**Q1：页面 404"资源不存在"？**
新管理页需在 `StaticViewRedirectConfig` 注册 `/xxx/` 重定向（历史教训：/mcp-gateway/ 漏配过）。重启后生效。

**Q2：`/api/chat/ask` 返回 500"系统繁忙"？**
多半是 LLM 通道问题：① sub2api 网关（8180）没起；② zen 上游全挂（看 `logs/metering.log`）；③ 老版本 LlmGateway 空 system 崩溃（已修复，确认代码版本）。

**Q3：MCP Client 连 `/mcp/sse` 报 401/403？**
需要头 `X-Tenant-Id: default` + `X-Api-Key: dev-key`（或 Authorization: Bearer dev-key）。key 在 application.yml `agent-platform.mcp.api-keys` 配置。

**Q4：工具调用返回"租户未被授权"？**
t_tool_grant 是 deny-by-default。去 `/mcp-gateway/` 授权管理页给该租户 + 工具授权，或 POST /api/mcp/grants。

**Q5：沙箱执行报 "Unable to find image"？**
首次需构建镜像：`docker build -t sboxes/python-311 -f docker/sandbox/Dockerfile.python .`（基础镜像走 daocloud，国内网络 OK）。

**Q6：流程实例卡在 WAITING_APPROVAL？**
人工节点等审批。30 分钟超时升级（escalated 标记，仍可补审批）。审批后自动继续。

**Q7：写操作没带幂等键报 400？**
设计如此：所有 WRITE/PAYMENT 工具调用必须带 `idempotencyKey`（如 `t1:app1:uuid`），同键重放返回首次结果。

**Q8：怎么评测知识库质量？**
```
python scripts\eval\golden_set_runner.py --json src\test\resources\golden-set\v1\golden-set.json
```
92 条自动评测：忠实度/召回/引用/转人工率，不达标退出码 1。

---

## 9. 术语表

| 术语 | 含义 |
|------|------|
| 租户（tenant） | 平台隔离单位，所有数据/授权带 `tenant_id`，默认 `default` |
| 应用（app） | 场景配置包（8 项 JSON），一个 appId 对应一个业务场景 |
| HITL | Human-In-The-Loop，写操作人工确认机制 |
| 幂等键 | 写操作的防重放令牌，同键二次调用返回首次结果 |
| 转人工（handoff） | 低置信/拒答/敏感意图时标记需人工接管 |
| Golden Set | 标准评测集（92 条），CI 门禁数据源 |
| MCP | Model Context Protocol，工具开放标准协议（双向网关） |
| 沙箱 | Docker 隔离执行环境（无网络/限额/强杀） |
| 置信度 | 合成分数 = 检索相似度×0.55 + 覆盖×0.25 + 引用×0.20，<0.25 转人工 |

---

## 10. 已知限制（截至 W12）

1. PAYMENT 级工具恒 403（双人复核未实现，P2 末期排期）
2. 转人工只到"标记+按钮"，无坐席工单系统（P2 遗留）
3. Reactor 流式线程 trace_id 不传播（审计流式 trace 为空，P2 遗留）
4. 沙箱 v1 固定 Python/Shell 双镜像；SQL 沙箱用事务只读，生产建议换只读账号
5. 工具选择评测 3 条歧义 badcase 待标注审查（档位/改签/退款规则）
