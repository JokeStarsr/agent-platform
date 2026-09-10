# 开放平台与成本看板设计（L1 接入层 + L7 数据层 · W17 P4 收口）

> 版本：v1.0 ｜ 状态：**已实现**（2026-09-10 完整交付，201 测试全绿，端到端验证通过） ｜ 依据：《开发排期》W17（开放平台与成本看板）、`20260910-data-agent-v2.md`（W16，已实现）、CLAUDE.md 设计文档铁律

---

## 1. 设计目的

**要解决的问题**：平台目前仅支持内部使用（Web UI + 内部 API），缺乏：
1. **开放平台能力**——第三方应用无法通过 API Key 调用平台能力（NL2SQL、RAG、Workflow 等）；
2. **Token 计量**——无法统计每个租户/应用/请求的 Token 消耗，成本无法归因；
3. **成本看板**——无法可视化查看用量趋势、费用分布、Top 消耗请求；
4. **预算告警**——租户无法设置日预算，超支时无法及时通知。

**不做会怎样**：
- 平台无法对外商业化（第三方无法接入）；
- 成本黑洞（不知道谁在烧钱、烧多少）；
- 无法做容量规划（不知道哪些租户/应用消耗最多）；
- 租户超支无法预警（月底才发现账单爆炸）。

**核心风险**：Token 计量的**准确性**——必须确保每次 LLM 调用都被记录（漏记 = 成本归因失真），且计量数据与 LLM Provider 账单对得上（误差 ≤ 5%）。

**范围**：本设计实现 **L1 开放平台 + L7 成本看板 v1**——①API Key 认证与租户限流（OpenApiGateway）；②Token 计量管道（TokenMeterService）；③成本看板服务（CostDashboardService）；④预算告警系统（BudgetAlertService）；⑤成本看板前端页面（/cost-dashboard/）；⑥开放平台接入文档与 SDK 示例。**不做**：Kafka + ClickHouse 实时流（v1 用 MySQL 聚合表起步，量大后演进）、多币种计费（v1 仅支持人民币）、OAuth2 第三方登录（v1 仅 API Key）。

---

## 2. 关键架构决策（ADR-20260910-03：API Key 认证 + MySQL 计量表起步）

### 2.1 API Key 认证：L1 接入层，Filter 拦截

```
第三方请求（Header: X-Api-Key）
  ↓
OpenApiFilter（L1 接入层）
  ├─ 提取 X-Api-Key
  ├─ 查 t_api_key 表（验证 + 获取 tenant_id）
  ├─ 检查 API Key 状态（active/expired/disabled）
  ├─ 检查租户限流（t_tenant_quota：qps/daily_quota）
  ├─ 检查用量配额（t_token_usage：今日已用 vs daily_quota）
  ├─ 通过 → 注入 X-Tenant-Id，转发到后端
  └─ 拒绝 → 返回 401/403/429
```

- **为什么用 Filter 而非 Interceptor**：Filter 在 Spring MVC 之前执行，可以拦截所有请求（包括静态资源）。Interceptor 只能拦截 Controller 方法，无法拦截 /mcp/sse 等非 MVC 端点。开放平台 API 需要拦截所有 /api/open/** 请求，Filter 更合适。
- **为什么不用 Spring Security**：Spring Security 配置复杂（需要定义 SecurityFilterChain、AuthenticationProvider），且平台已有 McpAuthFilter（W10）先例。复用 Filter 模式更一致。
- **不做的方向**：OAuth2 第三方登录（v1 仅 API Key，OAuth2 留 W20+）；JWT 签名验证（API Key 已足够，JWT 增加复杂度）。

### 2.2 Token 计量：MySQL 聚合表起步，量大后演进 ClickHouse

```
LLM 调用（LlmGateway.generate）
  ↓
TokenMeterService（L7 数据层）
  ├─ 记录调用元数据：
  │    - trace_id, tenant_id, app_id, model_name
  │    - prompt_tokens, completion_tokens, total_tokens
  │    - cost（按 model_name 单价计算）
  │    - duration_ms, created_at
  ├─ 写入 t_token_usage（明细表，按月分区）
  ├─ 更新 t_token_usage_daily（日聚合表，按 tenant_id + app_id + model_name 分组）
  └─ 检查预算告警（BudgetAlertService）
```

- **为什么用 MySQL 而非 ClickHouse**：v1 阶段调用量不大（预估日均 1 万次 LLM 调用），MySQL 聚合表足够（单表百万行，查询 < 100ms）。ClickHouse 适合亿级数据，引入增加运维复杂度（独立集群、数据同步）。**量大后演进**：日均 > 10 万次时迁移 ClickHouse。
- **为什么用双表（明细 + 聚合）**：明细表（t_token_usage）保留完整调用记录（用于审计、调试、Top 消耗查询），聚合表（t_token_usage_daily）加速看板查询（按日/租户/应用/模型分组，避免全表扫描）。
- **不做的方向**：Kafka 实时流（v1 同步写入 MySQL，量大后演进）；ClickHouse（v1 MySQL 足够）。

### 2.3 成本看板：租户/应用/模型三维度

```
CostDashboardService（L7 数据层）
  ├─ 用量统计：
  │    - 按租户：今日/本周/本月 Token 消耗、费用、环比
  │    - 按应用：各应用 Token 消耗占比
  │    - 按模型：各模型调用次数、费用占比
  ├─ Top 消耗请求：
  │    - 今日 Token 消耗最高的 10 个请求（trace_id + question + tokens）
  ├─ 趋势图表：
  │    - 近 7 天 Token 消耗趋势（折线图）
  │    - 近 7 天费用趋势（折线图）
  └─ 预算告警：
       - 租户日预算使用率（进度条）
       - 告警历史（80%/100% 两档通知）
```

- **为什么三维度（租户/应用/模型）**：覆盖成本归因的核心场景——"谁在烧钱"（租户）、"哪个应用在烧"（应用）、"哪个模型最贵"（模型）。三维度足够回答 90% 的成本问题。
- **为什么不做实时看板**：v1 阶段数据量不大，分钟级延迟可接受（t_token_usage_daily 每分钟聚合一次）。实时看板需要 WebSocket + 流计算，增加复杂度。
- **不做的方向**：实时看板（v1 分钟级延迟足够）；自定义维度（v1 固定三维度，量大后支持自定义）。

### 2.4 预算告警：80%/100% 两档通知

```
BudgetAlertService（L7 数据层）
  ├─ 每次 Token 计量后检查：
  │    - 今日已用 vs 日预算（t_tenant_quota.daily_budget）
  │    - 使用率 = 今日已用 / 日预算
  ├─ 触发告警：
  │    - 使用率 ≥ 80% → 发送"预算预警"通知（邮件/Webhook）
  │    - 使用率 ≥ 100% → 发送"预算超支"通知 + 熔断（拒绝新请求）
  ├─ 告警去重：
  │    - 同一租户同一档位每日仅告警一次（t_budget_alert_history）
  └─ 告警历史：
       - 记录告警时间、租户、使用率、通知方式
```

- **为什么 80%/100% 两档**：80% 是"预警"（还有余量，可以优化），100% 是"超支"（必须熔断）。两档覆盖"提前预警"和"强制熔断"两个场景。
- **为什么每日仅告警一次**：避免告警风暴（租户持续超支时，每小时告警一次会刷屏）。每日一次足够提醒租户关注。
- **不做的方向**：自定义告警阈值（v1 固定 80%/100%，量大后支持自定义）；多通知渠道（v1 仅邮件/Webhook，量大后支持短信/钉钉）。

---

## 3. API Key 认证与租户限流设计（L1 接入层）

### 3.1 表结构

```sql
CREATE TABLE t_api_key (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       VARCHAR(64) NOT NULL,
    api_key         VARCHAR(128) NOT NULL UNIQUE,
    api_key_hash    VARCHAR(64) NOT NULL,  -- SHA-256 哈希（存储哈希而非明文）
    name            VARCHAR(128) NOT NULL,  -- API Key 名称（如"生产环境"）
    status          VARCHAR(16) NOT NULL DEFAULT 'active',  -- active/expired/disabled
    expires_at      TIMESTAMPTZ,  -- 过期时间（NULL 表示永不过期）
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE t_tenant_quota (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       VARCHAR(64) NOT NULL UNIQUE,
    qps             INT NOT NULL DEFAULT 10,  -- 每秒请求数上限
    daily_quota     BIGINT NOT NULL DEFAULT 100000,  -- 日 Token 配额
    daily_budget    DECIMAL(10,2) NOT NULL DEFAULT 100.00,  -- 日预算（元）
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_api_key_hash ON t_api_key(api_key_hash);
CREATE INDEX idx_api_key_tenant ON t_api_key(tenant_id);
```

### 3.2 API Key 生成与验证

```java
// 生成 API Key
String apiKey = "sk-" + UUID.randomUUID().toString().replace("-", "");
String apiKeyHash = DigestUtils.sha256Hex(apiKey);

// 存储：api_key（明文，用于展示）+ api_key_hash（哈希，用于验证）
apiKeyRepository.insert(tenantId, apiKey, apiKeyHash, name);

// 验证：提取 X-Api-Key → 哈希 → 查 t_api_key
String providedKey = request.getHeader("X-Api-Key");
String providedHash = DigestUtils.sha256Hex(providedKey);
ApiKey apiKey = apiKeyRepository.findByHash(providedHash);
```

### 3.3 租户限流

```java
// 检查 QPS 限流
long currentQps = rateLimiter.getQps(tenantId);
if (currentQps > quota.qps()) {
    throw new BizException(429, "租户限流：QPS 超限");
}

// 检查日 Token 配额
long todayUsage = tokenUsageRepository.getDailyUsage(tenantId, LocalDate.now());
if (todayUsage >= quota.dailyQuota()) {
    throw new BizException(429, "租户限流：日 Token 配额已用尽");
}
```

### 3.4 管理 API

```
POST /api/open/keys
Request: {"name": "生产环境", "expiresAt": "2027-01-01T00:00:00Z"}
Response: {"apiKey": "sk-abc123..."}（仅创建时返回一次明文）

GET /api/open/keys
Response: [{"id": 1, "name": "生产环境", "status": "active", "expiresAt": "..."}]

DELETE /api/open/keys/{id}
Response: {"success": true}
```

---

## 4. Token 计量管道设计（L7 数据层）

### 4.1 表结构

```sql
CREATE TABLE t_token_usage (
    id              BIGSERIAL PRIMARY KEY,
    trace_id        VARCHAR(64) NOT NULL,
    tenant_id       VARCHAR(64) NOT NULL,
    app_id          VARCHAR(64),
    model_name      VARCHAR(64) NOT NULL,
    prompt_tokens   INT NOT NULL DEFAULT 0,
    completion_tokens INT NOT NULL DEFAULT 0,
    total_tokens    INT NOT NULL DEFAULT 0,
    cost            DECIMAL(10,4) NOT NULL DEFAULT 0,  -- 费用（元）
    duration_ms     BIGINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
) PARTITION BY RANGE (created_at);  -- 按月分区

CREATE TABLE t_token_usage_daily (
    id              BIGSERIAL PRIMARY KEY,
    stat_date       DATE NOT NULL,
    tenant_id       VARCHAR(64) NOT NULL,
    app_id          VARCHAR(64),
    model_name      VARCHAR(64) NOT NULL,
    total_calls     BIGINT NOT NULL DEFAULT 0,
    total_tokens    BIGINT NOT NULL DEFAULT 0,
    total_cost      DECIMAL(10,2) NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (stat_date, tenant_id, app_id, model_name)
);

CREATE INDEX idx_token_usage_trace ON t_token_usage(trace_id);
CREATE INDEX idx_token_usage_tenant ON t_token_usage(tenant_id, created_at DESC);
CREATE INDEX idx_token_usage_daily_tenant ON t_token_usage_daily(tenant_id, stat_date DESC);
```

### 4.2 计量采集流程

```java
// LlmGateway.generate 调用后
TokenMeterService.record(
    traceId,
    tenantId,
    appId,
    modelName,
    promptTokens,
    completionTokens,
    totalTokens,
    cost,  // 按模型单价计算
    durationMs
);

// TokenMeterService.record 实现
public void record(...) {
    // 1. 写入明细表
    tokenUsageRepository.insert(...);

    // 2. 更新日聚合表（UPSERT）
    tokenUsageDailyRepository.upsert(
        LocalDate.now(),
        tenantId,
        appId,
        modelName,
        totalTokens,
        cost
    );

    // 3. 检查预算告警
    budgetAlertService.check(tenantId);
}
```

### 4.3 模型单价配置

```yaml
# application.yml
token-pricing:
  models:
    claude-sonnet-4-5-20250929:
      prompt: 0.000003  # 元/Token
      completion: 0.000015
    gpt-4o:
      prompt: 0.000005
      completion: 0.000015
    deepseek-chat:
      prompt: 0.000001
      completion: 0.000002
```

---

## 5. 成本看板服务设计（L7 数据层）

### 5.1 用量统计 API

```
GET /api/cost-dashboard/usage/tenant?period=today
Response:
{
  "period": "today",
  "totalTokens": 123456,
  "totalCost": 12.34,
  "yesterdayTokens": 100000,
  "yesterdayCost": 10.00,
  "tokenGrowthRate": 23.45,  // %
  "costGrowthRate": 23.40     // %
}

GET /api/cost-dashboard/usage/app?period=week
Response:
{
  "period": "week",
  "apps": [
    {"appId": "cs_customer_service", "tokens": 50000, "cost": 5.00, "percentage": 40.5},
    {"appId": "data_agent", "tokens": 30000, "cost": 3.00, "percentage": 24.3},
    ...
  ]
}

GET /api/cost-dashboard/usage/model?period=month
Response:
{
  "period": "month",
  "models": [
    {"modelName": "claude-sonnet-4-5-20250929", "calls": 5000, "cost": 50.00, "percentage": 60.2},
    {"modelName": "gpt-4o", "calls": 3000, "cost": 30.00, "percentage": 36.1},
    ...
  ]
}
```

### 5.2 Top 消耗请求 API

```
GET /api/cost-dashboard/top-requests?limit=10
Response:
{
  "requests": [
    {"traceId": "abc-123", "question": "上月销售额", "tokens": 5000, "cost": 0.05, "createdAt": "..."},
    {"traceId": "def-456", "question": "客户分析", "tokens": 4500, "cost": 0.045, "createdAt": "..."},
    ...
  ]
}
```

### 5.3 趋势图表 API

```
GET /api/cost-dashboard/trend?days=7
Response:
{
  "days": 7,
  "tokenTrend": [
    {"date": "2026-09-04", "tokens": 100000},
    {"date": "2026-09-05", "tokens": 110000},
    ...
  ],
  "costTrend": [
    {"date": "2026-09-04", "cost": 10.00},
    {"date": "2026-09-05", "cost": 11.00},
    ...
  ]
}
```

---

## 6. 预算告警系统设计（L7 数据层）

### 6.1 表结构

```sql
CREATE TABLE t_budget_alert_history (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       VARCHAR(64) NOT NULL,
    alert_type      VARCHAR(16) NOT NULL,  -- warning(80%)/critical(100%)
    usage_rate      DECIMAL(5,2) NOT NULL,  -- 使用率（如 85.5）
    today_usage     DECIMAL(10,2) NOT NULL,
    daily_budget    DECIMAL(10,2) NOT NULL,
    notified_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_budget_alert_tenant ON t_budget_alert_history(tenant_id, notified_at DESC);
```

### 6.2 告警检查流程

```java
public void check(String tenantId) {
    TenantQuota quota = quotaRepository.findByTenantId(tenantId);
    if (quota == null) return;  // 未设置预算

    BigDecimal todayCost = tokenUsageDailyRepository.getTodayCost(tenantId);
    BigDecimal usageRate = todayCost.divide(quota.dailyBudget(), 2, RoundingMode.HALF_UP)
                                     .multiply(BigDecimal.valueOf(100));

    // 检查 80% 预警
    if (usageRate.compareTo(BigDecimal.valueOf(80)) >= 0) {
        sendAlertIfNotSentToday(tenantId, "warning", usageRate, todayCost, quota.dailyBudget());
    }

    // 检查 100% 超支
    if (usageRate.compareTo(BigDecimal.valueOf(100)) >= 0) {
        sendAlertIfNotSentToday(tenantId, "critical", usageRate, todayCost, quota.dailyBudget());
        // 熔断：标记租户为"预算超支"，拒绝新请求
        quotaRepository.markBudgetExceeded(tenantId);
    }
}

private void sendAlertIfNotSentToday(String tenantId, String alertType, ...) {
    // 检查今日是否已发送同类型告警
    boolean alreadySent = alertHistoryRepository.hasAlertToday(tenantId, alertType);
    if (alreadySent) return;

    // 发送通知（邮件/Webhook）
    notificationService.sendBudgetAlert(tenantId, alertType, usageRate, todayCost, dailyBudget);

    // 记录告警历史
    alertHistoryRepository.insert(tenantId, alertType, usageRate, todayCost, dailyBudget);
}
```

### 6.3 通知渠道

```java
public interface NotificationService {
    void sendBudgetAlert(String tenantId, String alertType, BigDecimal usageRate,
                         BigDecimal todayCost, BigDecimal dailyBudget);
}

// 邮件通知
public class EmailNotificationService implements NotificationService {
    public void sendBudgetAlert(...) {
        String subject = "预算告警：" + (alertType.equals("warning") ? "预警 80%" : "超支 100%");
        String body = String.format(
            "租户 %s 今日已消耗 %.2f 元，预算 %.2f 元，使用率 %.2f%%",
            tenantId, todayCost, dailyBudget, usageRate
        );
        emailSender.send(tenantEmail, subject, body);
    }
}

// Webhook 通知
public class WebhookNotificationService implements NotificationService {
    public void sendBudgetAlert(...) {
        Map<String, Object> payload = Map.of(
            "tenant_id", tenantId,
            "alert_type", alertType,
            "usage_rate", usageRate,
            "today_cost", todayCost,
            "daily_budget", dailyBudget
        );
        httpClient.post(webhookUrl, payload);
    }
}
```

---

## 7. 成本看板前端页面设计（/cost-dashboard/）

### 7.1 页面结构

```
/cost-dashboard/index.html
  ├─ 顶部统计卡片（今日 Token/费用/环比）
  ├─ 趋势图表（近 7 天 Token/费用折线图）
  ├─ 用量分布（饼图：按应用/按模型）
  ├─ Top 消耗请求（表格）
  ├─ 预算告警（进度条 + 告警历史）
  └─ API Key 管理（创建/删除/列表）

/cost-dashboard/app.js
  ├─ 查询用量统计（GET /api/cost-dashboard/usage/*）
  ├─ 查询趋势数据（GET /api/cost-dashboard/trend）
  ├─ 查询 Top 请求（GET /api/cost-dashboard/top-requests）
  ├─ 查询预算告警（GET /api/cost-dashboard/budget）
  └─ 管理 API Key（POST/DELETE /api/open/keys）
```

### 7.2 静态路由配置

```java
// StaticViewRedirectConfig.java
{"/cost-dashboard/", "/cost-dashboard/index.html"},
```

---

## 8. 开放平台接入文档与 SDK 设计

### 8.1 接入文档结构

```markdown
# 开放平台接入指南

## 快速开始
1. 创建 API Key
2. 配置请求 Header
3. 调用 API

## API Key 管理
- 创建：POST /api/open/keys
- 列表：GET /api/open/keys
- 删除：DELETE /api/open/keys/{id}

## 请求格式
Header: X-Api-Key: sk-abc123...
Body: JSON

## 可用 API
- NL2SQL：POST /api/data-agent/query
- RAG：POST /api/rag/search
- Workflow：POST /api/workflow/execute

## 错误码
- 401：API Key 无效
- 403：API Key 已过期/禁用
- 429：租户限流（QPS/Token 配额超限）

## SDK 示例
- Python SDK
- Java SDK
- JavaScript SDK
```

### 8.2 SDK 示例代码

**Python SDK**:
```python
import requests

class AgentPlatformClient:
    def __init__(self, api_key, base_url="http://localhost:8082"):
        self.api_key = api_key
        self.base_url = base_url
        self.headers = {"X-Api-Key": api_key, "Content-Type": "application/json"}

    def query(self, question, max_rows=100):
        url = f"{self.base_url}/api/data-agent/query"
        data = {"question": question, "maxRows": max_rows}
        response = requests.post(url, json=data, headers=self.headers)
        return response.json()

# 使用示例
client = AgentPlatformClient(api_key="sk-abc123...")
result = client.query("上月销售额最高的 3 个产品")
print(result)
```

**Java SDK**:
```java
public class AgentPlatformClient {
    private final String apiKey;
    private final String baseUrl;
    private final HttpClient httpClient;

    public AgentPlatformClient(String apiKey, String baseUrl) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.httpClient = HttpClient.newHttpClient();
    }

    public Map<String, Object> query(String question, int maxRows) throws Exception {
        String url = baseUrl + "/api/data-agent/query";
        String body = String.format("{\"question\":\"%s\",\"maxRows\":%d}", question, maxRows);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("X-Api-Key", apiKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return new ObjectMapper().readValue(response.body(), Map.class);
    }
}
```

---

## 9. 分步实施计划

| 步骤 | 内容 | 产出 | 预计工时 |
|------|------|------|----------|
| 1 | API Key 认证与租户限流（OpenApiFilter + ApiKeyRepository） | L1 Filter + L7 表 + API | 1 天 |
| 2 | Token 计量管道（TokenMeterService + 双表） | L7 服务 + 表 + 计量采集 | 1 天 |
| 3 | 成本看板服务（CostDashboardService） | L7 服务 + 统计 API | 1 天 |
| 4 | 预算告警系统（BudgetAlertService + 通知） | L7 服务 + 邮件/Webhook | 0.5 天 |
| 5 | 成本看板前端页面（/cost-dashboard/） | HTML + JS + ECharts | 1 天 |
| 6 | 开放平台接入文档与 SDK | Markdown + Python/Java/JS 示例 | 0.5 天 |
| 7 | 集成测试 + 端到端验证 | 全链路跑通 | 1 天 |
| 8 | 文档回写 + 用户验收 | 设计文档 v1.1 | 0.5 天 |

**总计：6.5 天（W17 一周内完成）**

---

## 10. 风险与缓解

| 风险 | 缓解措施 |
|------|----------|
| **Token 计量漏记**（部分 LLM 调用未记录） | LlmGateway.generate 统一入口，所有调用必经 TokenMeterService；评测集验证计量覆盖率 100% |
| **计量数据与 Provider 账单不一致**（误差 > 5%） | 每日对账脚本（本地 t_token_usage_daily vs Provider 账单）；差异 > 5% 告警 |
| **预算告警风暴**（租户持续超支时频繁告警） | 同一租户同一档位每日仅告警一次（t_budget_alert_history 去重） |
| **API Key 泄露**（第三方应用代码库泄露 API Key） | API Key 仅创建时展示一次明文（后续仅展示前 8 位）；支持立即禁用（DELETE /api/open/keys/{id}） |

---

## 11. 依赖与前置条件

| 依赖 | 状态 | 说明 |
|------|------|------|
| W10 MCP 网关 | ✅ 已完成 | McpAuthFilter（API Key 认证先例） |
| W12 执行沙箱 | ✅ 已完成 | CodeSandboxService（SDK 示例代码执行） |
| L6 LlmGateway | ✅ 已完成 | Token 计量采集点 |
| L7 数据层 | ✅ 已有 | PostgreSQL + JdbcTemplate |

---

**请检查本设计文档，批准后开始实现。**
