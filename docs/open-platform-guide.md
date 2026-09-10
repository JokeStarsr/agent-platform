# Agent Platform 开放平台接入指南

> 版本：v1.0 | 最后更新：2026-09-10

本文档介绍如何通过 API Key 接入 Agent Platform 开放平台，调用 NL2SQL、RAG、Workflow 等能力。

---

## 目录

1. [快速开始](#快速开始)
2. [API Key 管理](#api-key-管理)
3. [请求格式](#请求格式)
4. [可用 API](#可用-api)
5. [错误码](#错误码)
6. [SDK 示例](#sdk-示例)
7. [限流与配额](#限流与配额)
8. [常见问题](#常见问题)

---

## 快速开始

### 1. 创建 API Key

```bash
curl -X POST http://localhost:8082/api/open/keys \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: default" \
  -d '{"name": "生产环境", "expiresAt": "2027-01-01T00:00:00Z"}'
```

**响应**（仅创建时返回一次明文，请妥善保存）：

```json
{
  "code": 0,
  "data": {
    "id": 1,
    "apiKey": "sk-abc123def456ghi789jkl012mno345pqr",
    "apiKeyPrefix": "sk-abc123d",
    "name": "生产环境",
    "expiresAt": "2027-01-01T00:00:00Z"
  }
}
```

### 2. 配置请求 Header

所有开放平台 API 请求必须携带 `X-Api-Key` 头：

```bash
curl -X POST http://localhost:8082/api/data-agent/query \
  -H "Content-Type: application/json" \
  -H "X-Api-Key: sk-abc123def456ghi789jkl012mno345pqr" \
  -d '{"question": "上月销售额最高的 3 个产品"}'
```

### 3. 调用 API

成功响应示例：

```json
{
  "code": 0,
  "data": {
    "question": "上月销售额最高的 3 个产品",
    "sql": "SELECT ...",
    "columns": ["产品名称", "总销售额"],
    "rows": [["产品A", 12345.67], ["产品B", 9876.54], ["产品C", 8765.43]],
    "rowCount": 3
  }
}
```

---

## API Key 管理

### 创建 API Key

```
POST /api/open/keys
```

**请求体**：
```json
{
  "name": "生产环境",
  "expiresAt": "2027-01-01T00:00:00Z"  // 可选，null 表示永不过期
}
```

**注意**：API Key 明文仅在创建时返回一次，后续查询仅返回前缀（如 `sk-abc123d...`）。

### 查询 API Key 列表

```
GET /api/open/keys
```

**响应**：
```json
{
  "code": 0,
  "data": [
    {
      "id": 1,
      "apiKeyPrefix": "sk-abc123d...",
      "name": "生产环境",
      "status": "active",
      "expiresAt": "2027-01-01T00:00:00Z",
      "createdAt": "2026-09-10T12:00:00Z"
    }
  ]
}
```

### 禁用 API Key

```
PUT /api/open/keys/{id}/disable
```

### 删除 API Key

```
DELETE /api/open/keys/{id}
```

---

## 请求格式

### 请求头

| Header | 必填 | 说明 |
|--------|------|------|
| `X-Api-Key` | ✅ | API Key（`sk-` 开头的 35 位字符串） |
| `Content-Type` | ✅ | `application/json` |

### 请求体

所有 API 使用 JSON 格式，示例：

```json
{
  "question": "上月销售额",
  "maxRows": 100
}
```

### 响应格式

所有 API 返回统一 JSON 格式：

```json
{
  "code": 0,        // 0 表示成功，非 0 表示错误
  "data": {...},    // 成功时返回数据
  "error": "...",   // 失败时返回错误码
  "message": "..."  // 失败时返回错误信息
}
```

---

## 可用 API

### 1. NL2SQL 数据查询

```
POST /api/data-agent/query
```

**请求体**：
```json
{
  "question": "上月销售额最高的 3 个产品",
  "maxRows": 100,
  "enableChart": true
}
```

**响应**：
```json
{
  "code": 0,
  "data": {
    "question": "上月销售额最高的 3 个产品",
    "sql": "SELECT p.name, SUM(oi.subtotal) AS total_sales FROM t_order_item oi JOIN t_product p ON oi.product_id = p.product_id JOIN t_order o ON oi.order_id = o.order_id WHERE o.status IN ('paid', 'shipped', 'completed') GROUP BY p.name ORDER BY total_sales DESC LIMIT 3",
    "columns": ["name", "total_sales"],
    "rows": [["产品A", 12345.67], ["产品B", 9876.54], ["产品C", 8765.43]],
    "rowCount": 3,
    "chartResult": {
      "chartType": "bar",
      "description": "柱状图（分类对比）",
      "echartsOption": {...}
    }
  }
}
```

### 2. NL2SQL + 结论复算校验

```
POST /api/data-agent/query/verify
```

**请求体**：
```json
{
  "question": "上月销售额和增长率",
  "maxRows": 100,
  "requireVerification": true
}
```

**响应**（增加 `verification` 字段）：
```json
{
  "code": 0,
  "data": {
    "question": "上月销售额和增长率",
    "sql": "...",
    "columns": ["month", "total_sales", "growth_rate"],
    "rows": [...],
    "verification": {
      "allPassed": true,
      "summary": "全部 3 个数值结论验证通过",
      "details": [
        {"description": "销售额", "claimValue": "12345.67", "recalcValue": "12345.67", "passed": true}
      ]
    }
  }
}
```

### 3. RAG 知识检索

```
POST /api/rag/search
```

**请求体**：
```json
{
  "query": "差旅报销标准",
  "topK": 5
}
```

### 4. Workflow 执行

```
POST /api/workflow/execute
```

**请求体**：
```json
{
  "workflowId": "travel_booking_workflow",
  "input": {"destination": "北京", "startDate": "2026-10-01", "endDate": "2026-10-05"}
}
```

---

## 错误码

| HTTP 状态码 | 错误码 | 说明 | 处理建议 |
|------------|--------|------|----------|
| 401 | `UNAUTHORIZED` | 缺少 X-Api-Key 或 API Key 无效 | 检查请求头是否包含正确的 API Key |
| 403 | `FORBIDDEN` | API Key 已过期/禁用，或租户配额未配置 | 联系管理员检查 API Key 状态和租户配额 |
| 429 | `TOO_MANY_REQUESTS` | 租户限流（QPS/Token 配额超限，或预算超支熔断） | 降低请求频率，或联系管理员提升配额/预算 |
| 400 | `BAD_REQUEST` | 请求参数错误 | 检查请求体 JSON 格式和必填字段 |
| 500 | `INTERNAL_ERROR` | 服务器内部错误 | 联系管理员，提供 trace_id 便于排查 |

---

## SDK 示例

### Python SDK

```python
import requests

class AgentPlatformClient:
    def __init__(self, api_key, base_url="http://localhost:8082"):
        self.api_key = api_key
        self.base_url = base_url
        self.headers = {
            "X-Api-Key": api_key,
            "Content-Type": "application/json"
        }

    def query(self, question, max_rows=100, enable_chart=True):
        """NL2SQL 数据查询"""
        url = f"{self.base_url}/api/data-agent/query"
        data = {
            "question": question,
            "maxRows": max_rows,
            "enableChart": enable_chart
        }
        response = requests.post(url, json=data, headers=self.headers)
        response.raise_for_status()
        return response.json()

    def query_with_verification(self, question, max_rows=100):
        """NL2SQL + 结论复算校验"""
        url = f"{self.base_url}/api/data-agent/query/verify"
        data = {
            "question": question,
            "maxRows": max_rows,
            "requireVerification": True
        }
        response = requests.post(url, json=data, headers=self.headers)
        response.raise_for_status()
        return response.json()

    def search(self, query, top_k=5):
        """RAG 知识检索"""
        url = f"{self.base_url}/api/rag/search"
        data = {"query": query, "topK": top_k}
        response = requests.post(url, json=data, headers=self.headers)
        response.raise_for_status()
        return response.json()

# 使用示例
client = AgentPlatformClient(api_key="sk-abc123def456ghi789jkl012mno345pqr")

# NL2SQL 查询
result = client.query("上月销售额最高的 3 个产品")
print(result)

# NL2SQL + 复算校验
result = client.query_with_verification("上月销售额和增长率")
print(result["data"]["verification"])

# RAG 检索
result = client.search("差旅报销标准")
print(result)
```

### Java SDK

```java
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

public class AgentPlatformClient {
    private final String apiKey;
    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public AgentPlatformClient(String apiKey, String baseUrl) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
    }

    public Map<String, Object> query(String question, int maxRows) throws Exception {
        String url = baseUrl + "/api/data-agent/query";
        String body = objectMapper.writeValueAsString(Map.of(
            "question", question,
            "maxRows", maxRows
        ));

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("X-Api-Key", apiKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return objectMapper.readValue(response.body(), Map.class);
    }

    // 使用示例
    public static void main(String[] args) throws Exception {
        AgentPlatformClient client = new AgentPlatformClient(
            "sk-abc123def456ghi789jkl012mno345pqr",
            "http://localhost:8082"
        );
        Map<String, Object> result = client.query("上月销售额最高的 3 个产品", 100);
        System.out.println(result);
    }
}
```

### JavaScript SDK

```javascript
class AgentPlatformClient {
  constructor(apiKey, baseUrl = 'http://localhost:8082') {
    this.apiKey = apiKey;
    this.baseUrl = baseUrl;
  }

  async query(question, maxRows = 100, enableChart = true) {
    const url = `${this.baseUrl}/api/data-agent/query`;
    const response = await fetch(url, {
      method: 'POST',
      headers: {
        'X-Api-Key': this.apiKey,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({ question, maxRows, enableChart })
    });

    if (!response.ok) {
      throw new Error(`HTTP ${response.status}: ${response.statusText}`);
    }

    return await response.json();
  }

  async search(query, topK = 5) {
    const url = `${this.baseUrl}/api/rag/search`;
    const response = await fetch(url, {
      method: 'POST',
      headers: {
        'X-Api-Key': this.apiKey,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({ query, topK })
    });

    if (!response.ok) {
      throw new Error(`HTTP ${response.status}: ${response.statusText}`);
    }

    return await response.json();
  }
}

// 使用示例
const client = new AgentPlatformClient('sk-abc123def456ghi789jkl012mno345pqr');

client.query('上月销售额最高的 3 个产品')
  .then(result => console.log(result))
  .catch(err => console.error(err));
```

---

## 限流与配额

### 租户级限流

| 配额项 | 默认值 | 说明 |
|--------|--------|------|
| QPS | 10 | 每秒请求数上限 |
| 日 Token 配额 | 100,000 | 每日 Token 消耗上限 |
| 日预算 | ¥100.00 | 每日费用上限（超支熔断） |

### 查询配额

```
GET /api/open/quota
```

**响应**：
```json
{
  "code": 0,
  "data": {
    "tenantId": "default",
    "qps": 10,
    "dailyTokenQuota": 100000,
    "dailyBudget": 100.00,
    "budgetExceeded": false
  }
}
```

### 预算告警

- **80% 预警**：使用率达到 80% 时发送预警通知
- **100% 超支熔断**：使用率达到 100% 时拒绝新请求（返回 429）

### 调整配额

联系管理员修改租户配额（QPS/Token/预算）。

---

## 常见问题

### Q1: API Key 泄露了怎么办？

立即禁用或删除泄露的 API Key：

```bash
curl -X PUT http://localhost:8082/api/open/keys/1/disable \
  -H "X-Tenant-Id: default"
```

然后创建新的 API Key。

### Q2: 收到 429 错误怎么办？

429 表示租户限流，可能原因：
- **QPS 超限**：降低请求频率
- **日 Token 配额用尽**：等待次日重置，或联系管理员提升配额
- **预算超支熔断**：联系管理员提升预算或清除熔断标记

### Q3: 如何查看我的用量？

访问成本看板页面：`http://localhost:8082/cost-dashboard/`

或调用 API：

```bash
curl http://localhost:8082/api/cost-dashboard/usage/tenant?period=today \
  -H "X-Tenant-Id: default"
```

### Q4: API Key 过期了怎么办？

创建新的 API Key 并更新客户端配置。过期的 API Key 无法续期。

---

## 技术支持

如有问题，请联系平台管理员或提交工单。

---

**文档版本历史**：
- v1.0 (2026-09-10): 初始版本，覆盖 API Key 管理、NL2SQL、RAG、SDK 示例
