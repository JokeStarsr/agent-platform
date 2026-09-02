# W09 应用工厂：应用配置化与管理 API

> 版本：v1.0 ｜ 状态：**已批准** (2026-09-02 计划审批通过) ｜ 依据：《开发排期》W09 应用工厂 · 客服迁移 · P2 收口；表结构见 `docs/design/table/20260902-app-table.md`

---

## 1. 设计目的

**要解决的问题**：平台把"应用"当成字符串透传——Prompt 四处硬编码（RagService.buildSystemPrompt / AgentRuntimeServiceImpl.buildSystemPrompt / WorkflowServiceImpl.LLM_SYSTEM / ContextController.SYSTEM）、工具授权无 per-app 白名单、配额靠 `AgentConfig.of(appId)` 前缀魔法、没有启停概念。新场景接入必须改代码。

**不做会怎样**：G2 目标"新场景实测 ≤2 周"无法实证；P2 铁律"Prompt 不得硬编码"无法收口；多场景并行管理不可行。

**要交付**：应用注册表 + 八项配置 schema + 配置解析/校验/装配 + 启停管理 API + 管理页；消费点迁移（客服/商旅）随 P1 二期进行。

## 2. 设计细节

### 2.1 分层位置

应用工厂落 **L3 `orchestration.appfactory` 包**（AppRegistry/AppDefinition/AppValidator/AppController/PromptCenter），与 AgentRuntime/Workflow 编排组件同层，可被 capability（RagService）向下依赖；不落 L2 app（ArchitectureTest 约束 app 层只能被 Access 访问）。**ArchitectureTest 零改动**。

### 2.2 装配机制

- **AppRegistry**（@Service，L3）：`@PostConstruct` 全量 load `t_app` → 内存 `Map<(tenantId,appId),AppDefinition>`；管理写 API 后单条 refresh。未知 appId → 返回 null，消费点回退现状（不破坏现有行为）。
- **AppAssembler**：AppDefinition → 消费侧产物：
  - `AgentConfig.of(appDef)`（quota → maxSteps/tokenBudget/timeoutMs/loopThreshold/maxConcurrency）
  - Prompt 渲染（`{maxSteps}`/`{tokenBudget}` 等占位符替换）
  - 工具白名单（List<ToolWhitelistItem>）
  - handoff 参数对象（threshold + weights）
- **PromptCenter**：`render(template, vars)` + `DEFAULT(key)` 默认模板注册（workflow-llm-node 等内部模板）；**不建 t_prompt 表**（prompt 内嵌 config_json）。

### 2.3 API 契约（统一 Result<T>，X-Tenant-Id 透传）

| 方法 | 路径 | 请求 | 响应 data | 说明 |
|------|------|------|-----------|------|
| GET | /api/apps?page=1&size=20 | - | PageResult\<AppRow> | 应用列表（按租户） |
| GET | /api/apps/{appId} | - | AppRow | 应用详情（404 若不存在） |
| POST | /api/apps | {appId,name,configJson} | AppRow | 创建（全量校验；重复 appId → 409） |
| POST | /api/apps/{appId}/start | - | AppRow | CREATED/SUSPENDED → ENABLED（校验通过才可启） |
| POST | /api/apps/{appId}/suspend | - | AppRow | ENABLED → SUSPENDED |

**AppRow**：`{appId, name, status, version, configJson, createdAt, updatedAt}`

**错误**：BizException（404 应用不存在 / 409 已存在或状态冲突 / 400 校验失败），GlobalExceptionHandler 统一包装。

### 2.4 AppValidator（创建/启用时全量校验）

- 必填：role.name / prompt.system / quota 各字段非空合法
- tools[].name 必须在 ToolRegistry 已注册（查注册中心）
- tools[].permission 不越级：禁止 PAYMENT（引擎全局 403 恒锁）
- quota：maxSteps ∈ [1,100]、tokenBudget > 0、timeoutMs > 0、maxConcurrency ∈ [1,50]
- handoff.enabled=true 时：threshold ∈ (0,1]、weights 三项非负

### 2.5 消费点改造（P1 二期，本计划含契约，P0 不实施）

| 消费点 | 改动 |
|--------|------|
| AgentRuntimeServiceImpl.submit | config==null 时查 AppRegistry 装配 quota；SUSPENDED → 409 "应用已停用" |
| AgentRuntimeServiceImpl.buildSystemPrompt | 读 AppDefinition.prompt.system + 模板渲染 |
| RagService.search/streamSearch | 增 appId 参数（空串回退 yml 兜底）；prompt/handoff 读 app 配置 |
| ToolEngineServiceImpl.invoke | resolve 后 AppToolGate：注册 app 且工具不在白名单 → 403；未注册 app → 放行 |
| ContextController.assemble | SYSTEM 取 app.prompt（AssembleReq.appId 已有） |
| WorkflowServiceImpl.LLM_SYSTEM | PromptCenter.DEFAULT("workflow-llm-node") |

### 2.6 种子应用（app-seeds/*.json，幂等 upsert）

- `cs_customer_service.json`：prompt 搬运现 RagService.buildSystemPrompt 全文；handoff 阈值 0.25/权重对齐 application.yml；quota 10步/32k/300s/并发5
- `tr_booking.json`：prompt 模板化（`{maxSteps}/{tokenBudget}/{history}`）；tools 白名单 8 个（policy_query/compare_flight/compare_hotel/book_order/cancel_order/refund_order/send_coupon/notify_user，权限按 READ/WRITE）；memory 开（fieldWhitelist name/company/home_city）；quota 25步/60k/500s/并发10

### 2.7 前端管理页

`static/apps/index.html + app.js`：应用列表表格（appId/name/status 徽章/version/configJson 折叠）+ 创建表单 + 启停按钮。复用 `common.css` + StaticViewRedirectConfig（`/apps/ → /apps/index.html`）。

## 3. 备选方案与放弃理由

| 方案 | 结论 | 理由 |
|------|------|------|
| AppRegistry 落 L2 app 层 | ❌ | 违反 ArchitectureTest（capability/orchestration 无法注入）；同层编排组件解决 |
| 配置存 classpath JSON 不落库 | ❌ | 启停/分页/在线修改无法满足；DB 为唯一权威（Workflow 先例） |
| 独立 t_prompt 表 | ❌ | 双表一致性与 join；复用需求未出现（演进预判见表设计 §10） |
| 受理建 api 时允许 PAYMENT 工具 | ❌ | 支付级强制 HITL 是安全红线，白名单只到 WRITE |

## 4. 合规/租户/血缘/保留策略影响

- **租户隔离**：t_app 行级（X-Tenant-Id 缺省 default；GLOBAL 平台应用可见可引用）；装配按 (tenantId,appId) 二元组，消费链路不跨租户
- **数据治理**：config_json 为业务配置（Prompt/配额/白名单），无 PII；版本号回滚锚点；无删除流
- **安全**：SUSPENDED 应用在消费点（AgentRun.submit）拒绝执行（409）；工具白名单默认拒绝（未注册 app 保持现状兼容）
- **血缘**：运行记录 app_id → 应用当前配置；配置 version+1 与运行记录解耦

## 5. 演进预判

- Prompt 复用/版本化 → 抽 t_prompt 表（version 兼容迁移）
- 知识库 source 前缀过滤 → kb.sourcePrefix 生效（含 RagService hybridSearch 改造）
- 应用级模型路由（LlmGateway 按 app 选模型）→ P3 MCP/模型服务阶段接入

## 6. 实施清单

- [x] 设计文档（table + api，用户计划审批通过）
- [x] schema-app.sql + AppRow/AppRepository（data.application）
- [x] AppDefinition/AppSeedRegistrar（app-seeds ×2）/AppRegistry/AppAssembler/AppValidator
- [x] AppController + static/apps 页 + StaticViewRedirectConfig 加 /apps/ + 首页入口卡片
- [x] AppValidatorTest/AppRegistryTest/AppControllerContractTest + ArchitectureTest 回归（99/99 绿）
- [x] P1 二期：消费点改造 + 双跑（2026-09-03 完成，见下）

**P1 二期落地记录（与该文档 §2.5 契约一致）**：
- 跨层消费用**依赖反转接口**（ArchitectureTest 零改动仍 4/4 绿）：`com.agent.capability.AppPromptProvider`（L4 接口 → L3 `OrchAppPromptProvider` 实现）、`com.agent.tool.AppToolGate`（L5 接口 → L3 `AppToolGateImpl` 实现，@Autowired(required=false) 可选注入）
- AgentRuntimeServiceImpl：submit 查 AppRegistry → SUSPENDED 409"应用已停用"；config==null 时 AppAssembler.toAgentConfig 装配应用配额；executeLoop 用应用 prompt.system/userTemplate 渲染（{maxSteps}/{tokenBudget}/{toolsJson}），无应用回退原硬编码
- RagService：search/streamSearch 增 appId 参数（空回退 yml）；应用 handoff 参数覆盖 @Value 默认（阈值+权重）
- RagController：SearchRequest 增可选 appId；客服前端 static/chat 已带 appId=cs_customer_service
- ToolEngineServiceImpl：invoke 前置 AppToolGate（注册 app 且工具不在白名单 → 403；未注册/空白名单放行）
- ContextController：assemble 的 SYSTEM 取应用 prompt（appId 非空时），回退 DEFAULT_SYSTEM
- WorkflowServiceImpl：LLM_SYSTEM 常量改为 PromptCenter.defaultPrompt("workflow-llm-node")
- AppDefinition 增 status 字段（withStatus）承载启停状态
- 双跑脚本 `scripts/eval/cs_dual_compare.py`：同一 golden-set 两链路（legacy vs app）对比；冒烟 6 条：handoff 翻转率 0%（闸门 ≤20%）、引用集一致、答案字符串差异均为 LLM 措辞非确定性（抽查语义等价）。全量 92 条双跑留给回归期执行
- 测试：109 全绿（新增 AgentRuntimeAppFactoryTest 2 / AppToolGateImplTest 4 / OrchAppPromptProviderTest 4）

## 7. 变更历史

| 日期 | 变更 | 状态 |
|------|------|------|
| 2026-09-02 | 初稿（随 W09 计划审批通过） | 已批准 |
| 2026-09-02 | P0 实现完成（见下） | 已实现 |