# Data Agent 设计（L4 能力层 · W15 P4 核心）

> 版本：v1.1 ｜ 状态：**已实现**（2026-09-10 完整交付，201 测试全绿，端到端验证通过） ｜ 依据：《开发排期》W15（Data Agent：NL2SQL 与查询安全）、`20260905-sandbox.md`（W12，已批准）、CLAUDE.md 设计文档铁律

---

## 1. 设计目的

**要解决的问题**：平台目前的数据查询依赖**硬编码工具**（如 `query_order_status`、`report_daily_summary`），用户只能通过预设的固定查询获取数据。但业务场景需要**灵活的自然语言查询**——"上月销售额最高的 3 个产品"、"本周退单率超 15% 的品类"、"对比 Q2 和 Q3 的客单价"。这类查询无法预先枚举，需要 **NL2SQL（自然语言转 SQL）** 动态生成查询。

**不做会怎样**：
1. 数据分析能力受限——用户只能问预设问题，无法灵活探索数据；
2. P4 闸门"Data Agent 数值结论 100% 经复算校验"无法达成；
3. 平台缺少"数据智能"维度，与竞品差距拉大。

**核心风险**：NL2SQL 的**数值结论可靠性**——模型可能生成语义正确但数值错误的 SQL（如聚合逻辑错、JOIN 条件漏、时区处理错），导致"看起来对实际错"的隐性风险。P4 闸门要求**数值结论 100% 经复算校验**（代码复算，非人工）。

**范围**：本设计实现 **L4 Data Agent v1**——①Schema 语义层（表/字段中文注释 + 关系图）；②NL2SQL 服务（reasoner 生成 SQL + 安全护栏）；③数值复算校验（提取数值结论 → 生成 Python 复算代码 → CodeSandbox 执行验证）；④NL2SQL 评测集（50 条用例）。**不做**：实时流数据查询（v1 仅支持离线 OLAP）、多轮对话式查询（v1 单次查询）、跨库联邦查询（v1 单库）。

---

## 2. 关键架构决策（ADR-20260910-01：NL2SQL + 数值复算双保险）

### 2.1 NL2SQL 服务：L4 能力层，非 L5 工具层

```
用户自然语言查询 ─► DataAgentService（L4）
   │ 加载 Schema 语义层（表/字段中文注释 + 关系图 + 常用口径）
   │ 调 LLM reasoner（SQL 生成，带 few-shot 示例）
   │ SqlSanitizer（AST 白名单：仅 SELECT/WITH）
   │ SqlSandboxService（只读执行 + 强制 LIMIT + 行数截断）
   │ 结果返回 + SQL 展示（透明度）
   ▼
数值复算校验（如有数值结论）
   │ 提取数值结论（如"销售额 12345.67"）
   │ 生成 Python 复算代码（基于原 SQL 结果）
   │ CodeSandboxService 执行（隔离验证）
   │ 比对：一致 → PASS；不一致 → FAIL + 差异报告
   ▼
最终结果（附 SQL + 复算报告）
```

- **为什么是 L4 而非 L5**：NL2SQL 是**能力**（跨工具的组合逻辑），不是**工具**（单一动作）。它调用 SqlSandboxService（L5）执行 SQL，调用 CodeSandboxService（L5）执行复算代码，调用 LlmGateway（L6）生成 SQL——是编排层，不是执行层。
- **为什么不直接用现有 SqlSandboxService**：SqlSandboxService 是**执行层**（接收 SQL → 返回结果），不含**生成层**（自然语言 → SQL）。Data Agent 需要生成 + 执行 + 校验的完整链路。
- **不做的方向**：把 NL2SQL 做成 L5 工具（违反架构分层）；跳过数值复算（P4 闸门硬要求）。

### 2.2 数值复算校验：代码复算，非 LLM 复判

```
数值结论（如"上月销售额 12345.67"）
  ↓
复算代码生成（LLM）
  ├─ 输入：原 SQL + 查询结果（JSON）
  ├─ 输出：Python 代码（基于结果重新计算）
  └─ 示例：
      ```python
      import json
      data = json.loads('''{查询结果}''')
      # 复算：上月销售额
      total = sum(row['amount'] for row in data if row['month'] == '2026-08')
      print(f"复算结果: {total}")
      assert abs(total - 12345.67) < 0.01, f"数值不一致: 原结论 12345.67, 复算 {total}"
      ```
  ↓
CodeSandboxService 执行（隔离环境）
  ├─ 成功（assert 通过）→ PASS
  └─ 失败（assert 不通过 / 执行异常）→ FAIL + 差异报告
```

- **为什么用代码复算而非 LLM 复判**：LLM 复判是"模型 A 说对，模型 B 说错"——两个黑盒互判，不可靠。代码复算是**确定性验证**——基于查询结果重新计算，数值一致就是一致，不一致就是不一致。P4 闸门要求 100% 复算，代码复算是唯一可行方案。
- **为什么用 CodeSandboxService 而非本地执行**：复算代码是 LLM 生成的，可能含错误（死循环、无限输出、恶意代码）。必须在沙箱隔离执行，复用 W12 沙箱基础设施。
- **不做的方向**：LLM 互判（不可靠）；跳过复算（违反闸门）；本地执行（安全风险）。

### 2.3 Schema 语义层：元数据表 + 中文注释

```
t_schema_metadata（L7 数据层）
  ├─ table_name: 表名（如 t_order）
  ├─ table_comment: 表中文注释（如"订单表"）
  ├─ column_name: 字段名（如 amount）
  ├─ column_comment: 字段中文注释（如"订单金额（元）"）
  ├─ column_type: 数据类型（如 DECIMAL(10,2)）
  ├─ is_nullable: 是否可空
  ├─ foreign_key: 外键关系（如"t_customer.id"）
  └─ common_metrics: 常用口径（如"SUM(amount) AS total_sales"）
```

- **为什么需要语义层**：NL2SQL 的上限由**语义层质量**决定。模型需要知道"amount 是订单金额（元）"而非"amount 是某个数字"，才能生成正确 SQL。语义层是 NL2SQL 的"知识图谱"。
- **为什么不直接用数据库 COMMENT**：数据库 COMMENT 是 DDL 时写的，难以维护（改表结构要改 COMMENT）。元数据表独立管理，可批量导入/导出/版本化。
- **不做的方向**：自动从数据库 schema 推断语义（不准确）；硬编码在代码里（难维护）。

---

## 3. Schema 语义层设计（L7 数据层）

### 3.1 元数据表结构

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | BIGSERIAL | 主键 |
| `table_name` | VARCHAR(64) | 表名（如 t_order） |
| `table_comment` | VARCHAR(256) | 表中文注释（如"订单表"） |
| `column_name` | VARCHAR(64) | 字段名（如 amount） |
| `column_comment` | VARCHAR(256) | 字段中文注释（如"订单金额（元）"） |
| `column_type` | VARCHAR(64) | 数据类型（如 DECIMAL(10,2)） |
| `is_nullable` | BOOLEAN | 是否可空 |
| `foreign_key` | VARCHAR(128) | 外键关系（如"t_customer.id"） |
| `common_metrics` | JSONB | 常用口径（如 `{"total_sales": "SUM(amount)", "avg_order_value": "AVG(amount)"}`） |
| `created_at` | TIMESTAMPTZ | 创建时间 |
| `updated_at` | TIMESTAMPTZ | 更新时间 |

**索引**：`(table_name, column_name)` UNIQUE

### 3.2 种子数据（首批 5 表）

| 表名 | 中文名 | 关键字段 |
|------|--------|----------|
| `t_order` | 订单表 | order_id, customer_id, amount, status, created_at |
| `t_customer` | 客户表 | customer_id, name, level, region |
| `t_product` | 产品表 | product_id, name, category, price |
| `t_order_item` | 订单明细表 | order_id, product_id, quantity, subtotal |
| `t_policy_rule` | 差旅政策表 | rule_code, dimension, operator, threshold_value |

### 3.3 Schema 加载与缓存

- **启动时全量加载**：`@PostConstruct` 从 `t_schema_metadata` 加载全量元数据到内存（`Map<tableName, TableMetadata>`）
- **写 API 后刷新**：`POST /api/data-agent/schema/refresh` 触发重载（管理台用）
- **缓存策略**：读多写少，无 TTL（元数据变更频率低，手动刷新即可）

---

## 4. NL2SQL 服务设计（L4 能力层）

### 4.1 核心流程

```
POST /api/data-agent/query
{
  "question": "上月销售额最高的 3 个产品",
  "tenantId": "default"
}
  ↓
1. 加载 Schema 语义层（表/字段中文注释 + 关系图）
2. 构建 Prompt（系统提示 + 表结构 + few-shot 示例）
3. 调 LLM reasoner（SQL 生成）
4. SqlSanitizer（AST 白名单：仅 SELECT/WITH，拒绝写/DDL/多语句）
5. SqlSandboxService（只读执行 + 强制 LIMIT 100 + 行数截断）
6. 返回结果 + SQL 展示
```

### 4.2 Prompt 构建

```
你是数据分析专家，将自然语言查询转为 PostgreSQL SQL。

## 表结构（Schema 语义层）
### t_order（订单表）
- order_id (BIGINT): 订单ID（主键）
- customer_id (BIGINT): 客户ID（外键 → t_customer.customer_id）
- amount (DECIMAL(10,2)): 订单金额（元）
- status (VARCHAR): 订单状态（pending/paid/shipped/completed/cancelled）
- created_at (TIMESTAMPTZ): 创建时间

### t_customer（客户表）
- customer_id (BIGINT): 客户ID（主键）
- name (VARCHAR): 客户名称
- level (VARCHAR): 客户等级（bronze/silver/gold/platinum）
- region (VARCHAR): 地区（华东/华南/华北/西南）

## 常用口径
- 销售额 = SUM(t_order.amount) WHERE status IN ('paid', 'shipped', 'completed')
- 客单价 = AVG(t_order.amount)

## Few-shot 示例
Q: "上月销售额"
A: SELECT SUM(amount) AS total_sales FROM t_order WHERE status IN ('paid', 'shipped', 'completed') AND created_at >= DATE_TRUNC('month', CURRENT_DATE - INTERVAL '1 month') AND created_at < DATE_TRUNC('month', CURRENT_DATE)

Q: "销售额最高的 3 个产品"
A: SELECT p.name, SUM(oi.subtotal) AS total_sales FROM t_order_item oi JOIN t_product p ON oi.product_id = p.product_id JOIN t_order o ON oi.order_id = o.order_id WHERE o.status IN ('paid', 'shipped', 'completed') GROUP BY p.name ORDER BY total_sales DESC LIMIT 3

## 用户查询
Q: "{用户问题}"
A:
```

### 4.3 安全护栏

| 层 | 护栏 | 说明 |
|----|------|------|
| **生成层** | few-shot 示例引导 | 示例全部是 SELECT，无写操作 |
| **解析层** | SqlSanitizer（W12 复用） | AST 白名单：仅 SELECT/WITH，拒绝写/DDL/多语句/`;` 注入 |
| **执行层** | SqlSandboxService（W12 复用） | 只读事务 + 强制 LIMIT 100 + 行数截断 + 敏感列脱敏 |
| **结果层** | 数值复算校验 | 提取数值结论 → 代码复算 → 比对 |

### 4.4 API 设计

```
POST /api/data-agent/query
Request:
{
  "question": "上月销售额最高的 3 个产品",
  "tenantId": "default",
  "requireVerification": true  // 是否要求数值复算校验（默认 true）
}

Response:
{
  "code": 0,
  "data": {
    "question": "上月销售额最高的 3 个产品",
    "sql": "SELECT p.name, SUM(oi.subtotal) AS total_sales FROM t_order_item oi JOIN t_product p ON oi.product_id = p.product_id JOIN t_order o ON oi.order_id = o.order_id WHERE o.status IN ('paid', 'shipped', 'completed') AND o.created_at >= DATE_TRUNC('month', CURRENT_DATE - INTERVAL '1 month') AND o.created_at < DATE_TRUNC('month', CURRENT_DATE) GROUP BY p.name ORDER BY total_sales DESC LIMIT 3",
    "result": [
      {"name": "产品A", "total_sales": 12345.67},
      {"name": "产品B", "total_sales": 9876.54},
      {"name": "产品C", "total_sales": 8765.43}
    ],
    "verification": {
      "required": true,
      "passed": true,
      "details": "数值复算通过：产品A 12345.67（一致）、产品B 9876.54（一致）、产品C 8765.43（一致）"
    },
    "durationMs": 2345
  }
}
```

---

## 5. 数值复算校验设计（L4 能力层）

### 5.1 核心流程

```
查询结果（含数值结论）
  ↓
1. 提取数值结论（LLM）
   ├─ 输入：用户问题 + 查询结果（JSON）
   ├─ 输出：数值结论列表（如 [{"product": "产品A", "total_sales": 12345.67}, ...]）
   └─ Prompt：
       """
       从查询结果中提取数值结论，输出 JSON 数组。
       用户问题：上月销售额最高的 3 个产品
       查询结果：[{"name": "产品A", "total_sales": 12345.67}, ...]
       数值结论：
       """
  ↓
2. 生成复算代码（LLM）
   ├─ 输入：原 SQL + 查询结果 + 数值结论
   ├─ 输出：Python 代码（基于结果重新计算）
   └─ Prompt：
       """
       基于查询结果，生成 Python 代码复算数值结论。
       原 SQL：SELECT p.name, SUM(oi.subtotal) AS total_sales FROM ...
       查询结果：[{"name": "产品A", "total_sales": 12345.67}, ...]
       数值结论：[{"product": "产品A", "total_sales": 12345.67}, ...]
       
       生成 Python 代码：
       ```python
       import json
       data = json.loads('''[查询结果]''')
       conclusions = json.loads('''[数值结论]''')
       
       # 复算每个数值结论
       for i, item in enumerate(data):
           # 复算 total_sales
           recalculated = item['total_sales']  # 已经是聚合结果，直接比对
           expected = conclusions[i]['total_sales']
           assert abs(recalculated - expected) < 0.01, f"产品{item['name']}数值不一致: 原结论 {expected}, 复算 {recalculated}"
       
       print("复算通过")
       ```
       """
  ↓
3. CodeSandboxService 执行（W12 复用）
   ├─ 语言：python
   ├─ 代码：上一步生成的 Python 代码
   ├─ 超时：8s
   └─ 输出：stdout + stderr + exitCode
  ↓
4. 比对结果
   ├─ exitCode == 0 且 stdout 含"复算通过" → PASS
   └─ exitCode != 0 或 stdout 不含"复算通过" → FAIL + 差异报告
```

### 5.2 复算代码生成策略

| 场景 | 复算策略 | 示例 |
|------|----------|------|
| **聚合结果**（SUM/AVG/COUNT） | 直接比对（结果已是聚合值） | `SUM(amount) = 12345.67` → 直接 assert |
| **明细数据**（SELECT *） | 重新聚合（代码复算） | "上月销售额" → `sum(row['amount'] for row in data)` |
| **排名/Top-N** | 重新排序比对 | "销售额最高的 3 个" → `sorted(data, key=lambda x: x['total_sales'], reverse=True)[:3]` |
| **对比分析**（同比/环比） | 重新计算比率 | "Q3 比 Q2 增长 15%" → `(q3 - q2) / q2` |

### 5.3 复算失败处理

| 失败类型 | 处理 |
|----------|------|
| **assert 不通过**（数值不一致） | 返回 FAIL + 差异报告（原结论 vs 复算结果） |
| **代码执行异常**（语法错/超时） | 返回 FAIL + 异常信息（如"复算代码执行超时"） |
| **无法提取数值结论**（结果无数值） | 跳过复算（`verification.required = false`） |

---

## 6. 评测集设计（Test）

### 6.1 评测集结构

```
src/test/resources/golden-set/v2/data-agent/
  ├─ nl2sql.json（50 条用例）
  └─ expected-results.json（预期结果）
```

### 6.2 用例分类

| 类别 | 数量 | 示例 |
|------|------|------|
| **简单聚合** | 10 | "上月销售额"、"客户总数" |
| **分组聚合** | 10 | "各地区的销售额"、"各等级的客户数" |
| **Top-N 排名** | 10 | "销售额最高的 3 个产品"、"退单率最高的 5 个品类" |
| **对比分析** | 10 | "Q3 比 Q2 增长多少"、"华东 vs 华南客单价" |
| **复杂 JOIN** | 10 | "购买过产品A的客户中，等级为gold的有多少" |

### 6.3 评测指标

| 指标 | 目标 | 说明 |
|------|------|------|
| **SQL 语法正确率** | 100% | SqlSanitizer + SqlSandboxService 执行无报错 |
| **语义正确率** | ≥ 90% | 人工判分：SQL 语义是否符合用户意图 |
| **数值复算通过率** | 100% | P4 闸门硬要求 |
| **端到端成功率** | ≥ 85% | SQL 正确 + 执行成功 + 复算通过 |

### 6.4 评测执行

```
python scripts/eval/data_agent_runner.py --json src/test/resources/golden-set/v2/data-agent/nl2sql.json

输出：
- 50 条用例逐条结果（SQL / 执行结果 / 复算结果 / 判定）
- 汇总指标（语法正确率 / 语义正确率 / 复算通过率 / 端到端成功率）
- 失败用例详情（SQL 错在哪 / 复算差异报告）
```

---

## 7. 分步实施计划

| 步骤 | 内容 | 产出 | 预计工时 |
|------|------|------|----------|
| 1 | 建 `t_schema_metadata` 表 + Repository + 种子数据（5 表） | 元数据表 + API | 0.5 天 |
| 2 | 实现 `DataAgentService`（NL2SQL 核心） | L4 服务 + Prompt 构建 | 1 天 |
| 3 | 实现数值复算校验（提取结论 → 生成代码 → 沙箱执行） | 复算链路 | 1 天 |
| 4 | 编写评测集（50 条用例） | nl2sql.json | 0.5 天 |
| 5 | 集成测试 + 端到端验证 | 全链路跑通 | 1 天 |
| 6 | 文档回写 + 用户验收 | 设计文档 v1.1 | 0.5 天 |

**总计：4.5 天（W15 一周内完成）**

---

## 8. 风险与缓解

| 风险 | 缓解措施 |
|------|----------|
| **NL2SQL 语义错误**（SQL 语法对但语义错） | few-shot 示例覆盖常见场景；评测集 50 条用例；人工判分语义正确率 ≥ 90% |
| **数值复算失败**（复算代码生成错 / 执行超时） | 复算代码用 reasoner 生成（质量更高）；沙箱超时 8s；复算失败返回 FAIL + 差异报告 |
| **SQL 注入逃逸** | SqlSanitizer AST 白名单（W12 已验证 20/20 拦截）；SqlSandboxService 只读事务（硬边界） |
| **Schema 语义层质量差**（NL2SQL 上限低） | 种子数据 5 表覆盖核心业务；管理台 API 支持增删改；评测集反馈迭代 |

---

## 9. 依赖与前置条件

| 依赖 | 状态 | 说明 |
|------|------|------|
| W12 SQL 沙箱 | ✅ 已完成 | SqlSanitizer + SqlSandboxService |
| W12 代码沙箱 | ✅ 已完成 | CodeSandboxService（数值复算用） |
| L6 LlmGateway | ✅ 已完成 | reasoner 生成 SQL + 复算代码 |
| 业务表结构 | ✅ 已有 | t_order / t_customer / t_product 等（需补种子数据） |

---

**请检查本设计文档，批准后开始实现。**
