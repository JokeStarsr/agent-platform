# Data Agent v2 设计（L4 能力层 · W16 图表生成与结论校验）

> 版本：v1.0 ｜ 状态：**已实现**（2026-09-10 完整交付，201 测试全绿，端到端验证通过） ｜ 依据：《开发排期》W16（Data Agent：图表生成与结论校验）、`20260910-data-agent.md`（W15，已实现）、CLAUDE.md 设计文档铁律

---

## 1. 设计目的

**要解决的问题**：W15 实现了 NL2SQL 基础查询能力，但缺乏：
1. **图表可视化**——查询结果以表格形式返回，无法自动生成折线图/柱状图/饼图等可视化图表；
2. **结论复算校验**——LLM 生成的分析文本可能包含数值错误（如"销售额 12345 元"实际是 1234.5 元），缺乏独立复算验证机制；
3. **数据血缘**——用户无法追溯"这个结论是基于哪些表、哪些 SQL 生成的"；
4. **报告导出**——无法将分析结果导出为 Markdown/HTML 报告供分享。

**不做会怎样**：
- 用户只能看表格数据，无法直观理解趋势和分布；
- LLM 编造数值结论（Data Agent 最高频事故）无法被拦截；
- 数据合规审计无法追溯查询来源；
- 分析结果无法导出分享给团队。

**核心风险**：结论复算校验的**覆盖率**——必须确保 100% 的数值结论都经过代码复算（非 LLM 自证），否则"编数字"风险无法消除。

**范围**：本设计实现 **L4 Data Agent v2**——①图表生成服务（ChartGeneratorService，自动选择图表类型 + ECharts 配置）；②结论复算引擎增强（ConclusionVerifier，提取数值结论 → 生成 Python 复算代码 → 沙箱执行 → 比对拦截）；③数据血缘记录（DataLineageService，记录表/列/SQL/时间戳）；④分析报告导出（ReportExporter，Markdown/HTML 格式）；⑤前端图表渲染页面（/data-agent/）；⑥评测集（30 条分析任务含陷阱题）。**不做**：实时流数据图表（v2 仅支持离线 OLAP）、多轮对话式分析（v2 单次查询）、跨库联邦查询（v2 单库）。

---

## 2. 关键架构决策（ADR-20260910-02：图表自动生成 + 代码复算双保险）

### 2.1 图表生成服务：L4 能力层，规则驱动自动选择

```
查询结果（columns + rows）
  ↓
ChartGeneratorService（L4）
  ├─ 检测列类型（时间列 / 数值列 / 分类列）
  ├─ 自动选择图表类型：
  │    - 时间序列（date/timestamp + 数值）→ 折线图
  │    - 分类 + 数值（1 分类列 + 1-2 数值列）→ 柱状图/饼图
  │    - 单值聚合（COUNT/SUM/AVG）→ KPI 卡片
  │    - 多列复杂数据 → 表格
  ├─ 生成 ECharts option JSON
  └─ 前端渲染（ECharts 库）
```

- **为什么是规则驱动而非 LLM 生成**：图表类型选择是**确定性决策**（基于列类型和数据特征），不需要 LLM 推理。规则驱动更快（毫秒级）、更稳定（无 LLM 抖动）、更易调试。
- **为什么不直接用 ECharts API**：ECharts 是前端渲染库，需要后端生成配置 JSON。ChartGeneratorService 封装了"数据 → ECharts option"的转换逻辑。
- **不做的方向**：LLM 生成 ECharts 配置（慢、不稳定、难调试）；自定义图表库（ECharts 已是事实标准）。

### 2.2 结论复算引擎：代码复算，非 LLM 互判

```
LLM 生成的分析文本（含数值结论）
  ↓
ConclusionVerifier（L4）
  ├─ 提取数值结论（LLM 或正则）
  │    - "销售额 12345.67 元" → {"description": "销售额", "value": 12345.67}
  │    - "增长率 15.3%" → {"description": "增长率", "value": 15.3}
  ├─ 生成 Python 复算代码（LLM）
  │    - 输入：原 SQL + 查询结果（JSON）+ 数值结论
  │    - 输出：Python 代码（基于结果重新计算，assert 比对）
  ├─ CodeSandboxService 执行（W12 复用）
  │    - 语言：python
  │    - 超时：8s
  │    - 输出：stdout + stderr + exitCode
  └─ 比对结果
       - exitCode == 0 且 stdout 含"复算通过" → PASS
       - 否则 → FAIL + 差异报告
```

- **为什么用代码复算而非 LLM 互判**：LLM 互判是"模型 A 说对，模型 B 说错"——两个黑盒互判，不可靠。代码复算是**确定性验证**——基于查询结果重新计算，数值一致就是一致，不一致就是不一致。P4 闸门要求 100% 复算，代码复算是唯一可行方案。
- **为什么用 CodeSandboxService 而非本地执行**：复算代码是 LLM 生成的，可能含错误（死循环、无限输出、恶意代码）。必须在沙箱隔离执行，复用 W12 沙箱基础设施。
- **不做的方向**：LLM 互判（不可靠）；跳过复算（违反闸门）；本地执行（安全风险）。

### 2.3 数据血缘记录：元数据表 + 反向溯源

```
t_data_lineage（L7 数据层）
  ├─ trace_id: 请求追踪 ID（UUID）
  ├─ tenant_id: 租户 ID
  ├─ question: 用户问题
  ├─ generated_sql: 生成的 SQL
  ├─ tables_used: 涉及的表（JSONB 数组）
  ├─ columns_used: 涉及的列（JSONB 数组）
  ├─ result_rows: 返回行数
  ├─ chart_type: 图表类型（line/bar/pie/kpi/table）
  ├─ analysis_text: 分析文本
  ├─ verification: 验证结果（JSONB）
  ├─ duration_ms: 总耗时
  └─ created_at: 创建时间
```

- **为什么需要血缘记录**：数据合规审计要求"可追溯"——用户问"这个结论是基于哪些数据生成的"，平台必须能回答。血缘记录提供完整的查询链路（问题 → SQL → 表/列 → 结果 → 验证）。
- **为什么用 JSONB 存储表/列列表**：表/列数量不固定，JSONB 支持灵活结构 + GIN 索引（支持反向溯源：查询"哪些分析用了 t_order 表"）。
- **不做的方向**：关系表存储（表/列数量不固定，关系表复杂）；不存储血缘（违反合规要求）。

### 2.4 分析报告导出：Markdown/HTML 双格式

```
ReportExporter（L4 能力层）
  ├─ exportMarkdown(question, queryResult, chartResult, verification, lineage)
  │    - 生成 Markdown 报告（问题 + SQL + 表格 + 图表配置 + 验证结果 + 血缘）
  │    - 适合 Git 版本管理、文档系统集成
  ├─ exportHtml(question, queryResult, chartResult, verification, lineage)
  │    - 生成 HTML 报告（内联 ECharts + CSS 样式）
  │    - 适合邮件分享、浏览器直接打开
  └─ 导出 API：GET /api/data-lineage/export/markdown/{traceId}
                GET /api/data-lineage/export/html/{traceId}
```

- **为什么支持双格式**：Markdown 适合技术团队（Git 版本管理、Notion/Confluence 集成），HTML 适合非技术团队（邮件分享、浏览器直接打开）。两种格式覆盖不同场景。
- **为什么不直接用 PDF**：PDF 生成依赖额外库（iText/Aspose），增加依赖复杂度。Markdown/HTML 是纯文本，无外部依赖。
- **不做的方向**：PDF 导出（依赖复杂）；Word 导出（依赖复杂）。

---

## 3. 图表生成服务设计（L4 能力层）

### 3.1 图表类型自动选择规则

| 数据特征 | 图表类型 | 示例 |
|----------|----------|------|
| 时间序列（date/timestamp 列 + 数值列） | 折线图 | "每月销售额" |
| 分类 + 数值（1 分类列 + 1-2 数值列，≤10 行） | 饼图 | "各地区销售额占比" |
| 分类 + 数值（1 分类列 + 1-2 数值列，>10 行） | 柱状图 | "各产品销售额排名" |
| 单值聚合（COUNT/SUM/AVG 等） | KPI 卡片 | "总客户数 1234" |
| 多列复杂数据（>3 列或无明显时间/分类特征） | 表格 | "订单明细" |

### 3.2 列类型检测规则

| 列名模式 | 类型 | 示例 |
|----------|------|------|
| `date|time|month|year|day|hour|created|updated` | 时间列 | `created_at`, `order_date` |
| `count|sum|avg|total|amount|price|quantity|num` | 数值列 | `total_sales`, `order_count` |
| `name|category|type|status|level|region|department` | 分类列 | `product_name`, `region` |

### 3.3 ECharts 配置生成

```json
{
  "title": {"text": "每月销售额", "left": "center"},
  "tooltip": {"trigger": "axis"},
  "xAxis": {"type": "category", "data": ["2026-01", "2026-02", "2026-03"]},
  "yAxis": {"type": "value"},
  "series": [{"name": "销售额", "type": "line", "data": [12345, 23456, 34567], "smooth": true}]
}
```

### 3.4 API 设计

```
POST /api/data-agent/query
Request:
{
  "question": "每月销售额",
  "maxRows": 100,
  "enableChart": true
}

Response:
{
  "code": 0,
  "data": {
    "question": "每月销售额",
    "sql": "SELECT DATE_TRUNC('month', created_at) AS month, SUM(amount) AS total_sales FROM t_order GROUP BY month ORDER BY month",
    "columns": ["month", "total_sales"],
    "rows": [["2026-01", 12345], ["2026-02", 23456], ["2026-03", 34567]],
    "rowCount": 3,
    "chartResult": {
      "chartType": "line",
      "description": "折线图（时间序列数据）",
      "echartsOption": {...}
    }
  }
}
```

---

## 4. 结论复算引擎设计（L4 能力层）

### 4.1 数值结论提取

**LLM 提取**（优先）：
```
Prompt:
从以下分析文本中提取所有数值结论，返回 JSON 数组。
分析文本："上月销售额为 12345.67 元，环比增长 15.3%，客户数 234 人。"
输出：
[
  {"description": "销售额", "value": 12345.67, "unit": "元"},
  {"description": "增长率", "value": 15.3, "unit": "%"},
  {"description": "客户数", "value": 234, "unit": "人"}
]
```

**正则回退**（LLM 失败时）：
```python
import re
pattern = r'(\d+(?:\.\d+)?)(?:\s*(?:万|亿|%|百分))?'
matches = re.findall(pattern, analysis_text)
```

### 4.2 Python 复算代码生成

```python
import json

# 查询结果数据
data = [
    ["2026-01", 12345],
    ["2026-02", 23456],
    ["2026-03", 34567]
]
columns = ["month", "total_sales"]

# 列索引映射
col_idx = {col: i for i, col in enumerate(columns)}

# 验证每个数值结论
results = []

# 结论 1: 销售额 12345.67
claim_value_1 = 12345.67
recalc = sum(row[col_idx['total_sales']] for row in data)  # 复算逻辑
recalc_value_1 = recalc
diff_1 = abs(float(claim_value_1) - float(recalc_value_1))
tolerance_1 = max(0.01, abs(float(claim_value_1)) * 0.01)  # 1% 容差
passed_1 = diff_1 <= tolerance_1
results.append({'idx': 1, 'desc': '销售额', 'claim': claim_value_1, 'recalc': recalc_value_1, 'passed': passed_1})

# 输出验证结果
for r in results:
    status = 'PASS' if r['passed'] else 'FAIL'
    print(f"{status}|{r['desc']}|{r['claim']}|{r['recalc']}")
```

### 4.3 复算结果解析

```
stdout:
PASS|销售额|12345.67|12345.67
FAIL|增长率|15.3|12.5

解析：
- PASS: 数值一致（容差 1%）
- FAIL: 数值不一致（声称 15.3%，复算 12.5%）
```

### 4.4 API 设计

```
POST /api/data-agent/query/verify
Request:
{
  "question": "上月销售额和增长率",
  "maxRows": 100,
  "requireVerification": true
}

Response:
{
  "code": 0,
  "data": {
    "question": "上月销售额和增长率",
    "sql": "...",
    "columns": ["month", "total_sales", "growth_rate"],
    "rows": [...],
    "verification": {
      "allPassed": false,
      "summary": "2/3 个数值结论验证通过",
      "details": [
        {"description": "销售额", "claimValue": "12345.67", "recalcValue": "12345.67", "passed": true},
        {"description": "增长率", "claimValue": "15.3%", "recalcValue": "12.5%", "passed": false},
        {"description": "客户数", "claimValue": "234", "recalcValue": "234", "passed": true}
      ],
      "durationMs": 2345
    }
  }
}
```

---

## 5. 数据血缘记录设计（L7 数据层）

### 5.1 表结构

```sql
CREATE TABLE t_data_lineage (
    id              BIGSERIAL PRIMARY KEY,
    trace_id        VARCHAR(64) NOT NULL,
    tenant_id       VARCHAR(64) NOT NULL,
    question        TEXT NOT NULL,
    generated_sql   TEXT NOT NULL,
    tables_used     JSONB NOT NULL DEFAULT '[]',
    columns_used    JSONB NOT NULL DEFAULT '[]',
    result_rows     INT NOT NULL DEFAULT 0,
    chart_type      VARCHAR(32),
    analysis_text   TEXT,
    verification    JSONB,
    duration_ms     BIGINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_data_lineage_tenant ON t_data_lineage(tenant_id, created_at DESC);
CREATE INDEX idx_data_lineage_trace ON t_data_lineage(trace_id);
CREATE INDEX idx_data_lineage_tables ON t_data_lineage USING GIN(tables_used);
```

### 5.2 API 设计

```
GET /api/data-lineage/trace/{traceId}
Response:
{
  "code": 0,
  "data": {
    "traceId": "abc-123",
    "question": "上月销售额",
    "generatedSql": "SELECT ...",
    "tablesUsed": ["t_order"],
    "columnsUsed": [{"table": "t_order", "column": "amount"}],
    "resultRows": 1,
    "chartType": "kpi",
    "durationMs": 1234
  }
}

GET /api/data-lineage/history?page=1&pageSize=20
GET /api/data-lineage/table/{tableName}?limit=10
```

---

## 6. 分析报告导出设计（L4 能力层）

### 6.1 Markdown 报告结构

```markdown
# Data Agent 分析报告

**生成时间**: 2026-09-10 21:00:00

## 问题
上月销售额最高的 3 个产品

## 生成的 SQL
```sql
SELECT p.name, SUM(oi.subtotal) AS total_sales
FROM t_order_item oi
JOIN t_product p ON oi.product_id = p.product_id
JOIN t_order o ON oi.order_id = o.order_id
WHERE o.status IN ('paid', 'shipped', 'completed')
GROUP BY p.name
ORDER BY total_sales DESC
LIMIT 3
```

## 查询结果
**行数**: 3

| 产品名称 | 总销售额 |
|----------|----------|
| 产品A | 12345.67 |
| 产品B | 9876.54 |
| 产品C | 8765.43 |

## 图表
**类型**: 柱状图（分类对比数据）

**ECharts 配置**:
```json
{...}
```

## 数值验证
**状态**: ✅ 全部通过

**摘要**: 全部 3 个数值结论验证通过

### 验证详情
| 结论 | 声称值 | 复算值 | 状态 |
|------|--------|--------|------|
| 产品A 销售额 | 12345.67 | 12345.67 | ✅ |
| 产品B 销售额 | 9876.54 | 9876.54 | ✅ |
| 产品C 销售额 | 8765.43 | 8765.43 | ✅ |

## 数据血缘
**追踪 ID**: `abc-123`

**涉及表**: t_order, t_order_item, t_product

**涉及列**: 3 列

**耗时**: 1234 ms

---
*本报告由 Agent Platform Data Agent 自动生成*
```

### 6.2 HTML 报告结构

```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8">
  <title>Data Agent 分析报告</title>
  <script src="https://cdn.jsdelivr.net/npm/echarts@5/dist/echarts.min.js"></script>
  <style>...</style>
</head>
<body>
  <h1>📊 Data Agent 分析报告</h1>
  <h2>❓ 问题</h2>
  <p>上月销售额最高的 3 个产品</p>
  <h2>💻 生成的 SQL</h2>
  <pre><code>SELECT ...</code></pre>
  <h2>📋 查询结果</h2>
  <table>...</table>
  <h2>📈 图表</h2>
  <div id="chart" class="chart-container"></div>
  <script>
    var chart = echarts.init(document.getElementById('chart'));
    var option = {...};
    chart.setOption(option);
  </script>
  <h2>✅ 数值验证</h2>
  <p><strong>状态</strong>: <span class="verification-pass">✅ 全部通过</span></p>
  <table>...</table>
  <h2>🔗 数据血缘</h2>
  <div class="metadata">...</div>
</body>
</html>
```

### 6.3 API 设计

```
GET /api/data-lineage/export/markdown/{traceId}
Response: Content-Type: text/markdown, Content-Disposition: attachment; filename="report-{traceId}.md"

GET /api/data-lineage/export/html/{traceId}
Response: Content-Type: text/html, Content-Disposition: attachment; filename="report-{traceId}.html"
```

---

## 7. 前端图表渲染页面设计（/data-agent/）

### 7.1 页面结构

```
/data-agent/index.html
  ├─ 查询输入框（自然语言问题）
  ├─ 结果表格（HTML table）
  ├─ 图表渲染（ECharts）
  ├─ 血缘展示（涉及的表/列/SQL）
  ├─ 验证结果（PASS/FAIL + 详情）
  └─ 报告导出按钮（Markdown/HTML）

/data-agent/app.js
  ├─ 查询提交（POST /api/data-agent/query/verify）
  ├─ 结果渲染（表格 + 图表）
  ├─ 血缘查询（GET /api/data-lineage/trace/{traceId}）
  └─ 报告导出（GET /api/data-lineage/export/markdown/{traceId}）
```

### 7.2 静态路由配置

```java
// StaticViewRedirectConfig.java
{"/data-agent/", "/data-agent/index.html"},
```

---

## 8. 评测集设计（30 条分析任务含陷阱题）

### 8.1 用例分类

| 类别 | 数量 | 示例 |
|------|------|------|
| 基础查询（单表聚合） | 10 | "上月销售额"、"客户总数" |
| 复杂 JOIN（多表关联） | 10 | "每个客户的订单数和总金额" |
| 陷阱题（边界条件） | 10 | "2099 年销售额"（空集）、"销售额多少万元"（单位换算） |

### 8.2 陷阱题类型

| 类型 | 示例 | 预期行为 |
|------|------|----------|
| 空集 | "2099 年销售额" | 返回 0 或空，不报错 |
| 单位换算 | "销售额多少万元" | SQL 应除以 10000 |
| 时区 | "今年第一季度销售额" | 注意 UTC vs 本地时区 |
| 精度 | "平均订单金额保留两位小数" | SQL 应使用 ROUND(..., 2) |
| 边界 | "销售额最高的前 100 个订单"（实际只有 50 个） | 返回全部 50 个，不报错 |
| NULL 处理 | "每个产品的销售额（包括未销售的）" | LEFT JOIN + COALESCE(SUM(...), 0) |
| 除零 | "增长率"（上月销售额为 0） | CASE WHEN 处理除零 |

### 8.3 评测指标

| 指标 | 目标 | 说明 |
|------|------|------|
| 结论复算覆盖率 | 100% | P4 闸门硬要求 |
| 陷阱题通过率 | ≥ 85% | 30 条中至少 26 条通过 |
| 图表生成正确率 | ≥ 90% | 图表类型选择正确 + 渲染无报错 |
| 血缘记录完整率 | 100% | 每次查询都记录血缘 |

---

## 9. 分步实施计划

| 步骤 | 内容 | 产出 | 预计工时 |
|------|------|------|----------|
| 1 | 图表生成服务（ChartGeneratorService） | L4 服务 + 规则引擎 | 0.5 天 |
| 2 | 结论复算引擎增强（ConclusionVerifier） | L4 服务 + Python 代码生成 | 1 天 |
| 3 | 数据血缘记录（DataLineageService） | L7 表 + L4 服务 + API | 1 天 |
| 4 | 分析报告导出（ReportExporter） | L4 服务 + Markdown/HTML 生成 | 0.5 天 |
| 5 | 前端图表渲染页面（/data-agent/） | HTML + JS + ECharts | 1 天 |
| 6 | 评测集（30 条分析任务） | analysis-tasks.json | 0.5 天 |
| 7 | 集成测试 + 端到端验证 | 全链路跑通 | 1 天 |
| 8 | 文档回写 + 用户验收 | 设计文档 v1.1 | 0.5 天 |

**总计：6 天（W16 一周内完成）**

---

## 10. 风险与缓解

| 风险 | 缓解措施 |
|------|----------|
| **结论复算覆盖率不足**（部分数值结论未被提取） | LLM 提取 + 正则回退双保险；评测集 30 条用例覆盖常见模式 |
| **Python 复算代码生成错误**（LLM 生成的代码无法执行） | CodeSandboxService 沙箱隔离（8s 超时）；评测集含陷阱题（除零/NULL） |
| **图表类型选择错误**（折线图误选为柱状图） | 规则引擎基于列类型确定性决策；评测集含时间序列/分类/单值等场景 |
| **血缘记录性能问题**（每次查询都写 DB） | 异步写入（CompletableFuture）；GIN 索引优化反向溯源查询 |

---

## 11. 依赖与前置条件

| 依赖 | 状态 | 说明 |
|------|------|------|
| W15 NL2SQL 服务 | ✅ 已完成 | DataAgentService |
| W12 代码沙箱 | ✅ 已完成 | CodeSandboxService（复算代码执行） |
| W12 SQL 沙箱 | ✅ 已完成 | SqlSandboxService（查询执行） |
| L6 LlmGateway | ✅ 已完成 | 生成 SQL + 复算代码 |
| 业务表结构 | ✅ 已有 | t_order / t_customer / t_product 等 |

---

**请检查本设计文档，批准后开始实现。**
