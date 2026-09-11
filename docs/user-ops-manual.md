# Agent Platform 用户手册与运维手册

> 版本：v1.0 ｜ 日期：2026-09-11 ｜ 配合前端使用说明书（/usage/）使用

---

## 第一部分：用户手册

### 1. 快速访问

| 页面 | 地址 | 用途 |
|------|------|------|
| 首页导航 | http://localhost:8082/ | 全模块入口 |
| 客服对话 | /chat/ | RAG 检索 + SSE 流式 + 转人工 |
| Agent 运行 | /agent/ | 单 Agent ReAct 循环监控 |
| Workflow | /workflow/ | 确定性 DAG 流程 |
| 应用工厂 | /apps/ | 应用配置与启停 |
| MCP 网关 | /mcp-gateway/ | 工具授权 + 出站连接 |
| 工具市场 | /tool-market/ | 工具注册/发布/上下架 |
| 多智能体 | /multi-agent/ | Supervisor/Pipeline 协作 |
| Skill Hub | /skill-hub/ | 技能包安装/发布 |
| 通用助手 | /assistant/ | 意图路由 + 技能执行 |
| Data Agent | /data-agent/ | NL2SQL + 图表 + 血缘 |
| 成本看板 | /cost-dashboard/ | 用量统计 + 预算告警 |
| 使用说明 | /usage/ | 完整操作指南 |

### 2. 租户隔离约定

- 所有 API 请求携带 `X-Tenant-Id` 头（如 `default`、`tenant_001`）
- 数据按租户隔离：知识库 Collection、记忆、Agent 运行、Workflow 实例、成本归因
- 越权访问返回 403（服务层校验 + 测试固化）

### 3. 调用约定

| 项 | 规则 |
|----|------|
| 统一响应 | `Result<T>`：`{code:0,data,...}`；非 0 = 业务异常 |
| 写操作 | 需幂等键（`Idempotency-Key`）；HITL 审批 |
| Trace | 响应头 `X-Trace-Id`，日志 MDC 关联 |
| 开放 API | 走 API Key（`X-Api-Key`）认证，租户限流+配额 |

---

## 第二部分：运维手册

### 4. 部署

```bash
# 环境要求
# - JDK 17
# - PostgreSQL 16 + pgvector
# - Docker（代码沙箱）

# 构建
mvn clean package -DskipTests

# 启动
java -jar target/agent-platform-1.0.0-SNAPSHOT.jar --server.port=8082

# 或开发模式
mvn spring-boot:run "-Dspring-boot.run.arguments=--server.port=8082"
```

### 5. 环境变量

| 变量 | 必填 | 说明 |
|------|------|------|
| `LLM_BASE_URL` | 否 | LLM 网关地址（默认 localhost:8180） |
| `LLM_API_KEY` | 是 | LLM API Key |
| `LLM_MODEL` | 否 | 默认模型名 |
| `ZHIPUAI_API_KEY` | 是 | 智谱 embedding（RAG 向量化） |
| `MCP_DEFAULT_API_KEY` | 否 | MCP 入站默认 key（默认 dev-key） |

### 6. 数据库

- 库：`agent_platform`（PostgreSQL + pgvector）
- 表：26+ 张（agent run/workflow/memory/policy/app/MCP/工具市场/多智能体/技能/血缘/Token 计量/openplatform/安全）
- DDL：`src/main/resources/schema-*.sql`（幂等，启动自动执行）
- 容器：`agent-platform-pg`（docker，pgvector/pg16）

### 7. 健康检查与监控

```bash
# 健康
GET /actuator/health

# 指标（Prometheus）
GET /actuator/prometheus

# 成本
GET /api/cost-dashboard/usage/tenant?period=today

# 缓存命中率
GET /api/data-agent/cache/stats

# 安全日志
GET /api/security/injection/logs
```

### 8. 备份与恢复

```bash
# PostgreSQL 备份
pg_dump -h localhost -U postgres agent_platform > backup.sql

# 恢复
psql -h localhost -U postgres agent_platform < backup.sql

# 关键表（优先备份）
# t_user_memory / t_agent_run / t_workflow_instance / t_rag_collection
```

### 9. 故障排查

| 症状 | 排查方向 |
|------|----------|
| App 启动失败 | 检查端口占用（8080/8082）、PostgreSQL 连接、LLM Key |
| LLM 调用 500 | 检查 LLM_BASE_URL 可达性、API Key 余额、sub2api 状态 |
| RAG 检索慢/空 | 检查 pgvector、知识库未索引、ZHIPUAI key |
| 沙箱执行失败 | 检查 Docker 运行、镜像 sboxes/python-311 |
| Token 看板为空 | 确认 LLM 调用已产生（TokenMeterService 打点） |
| 预算熔断 429 | /cost-dashboard/ 查看告警，管理员清熔断 |

### 10. 安全运维

- **注入检测**：POST /api/security/injection/detect（规则命中即拦截）
- **PII 脱敏**：POST /api/security/pii/mask（身份证/银行卡/手机/邮箱/密钥）
- **租户越权测试**：mvn test -Dtest=TenantIsolationTest（13 条矩阵）
- **API Key**：仅创建时展示一次明文；泄露立即禁用/删除
- **日志脱敏**：审计日志自动 MDC trace_id 关联

---

## 第三部分：开放平台（第三方接入）

### 快速开始

```bash
# 1. 创建 API Key
curl -X POST http://localhost:8082/api/open/keys \
  -H "Content-Type: application/json" -H "X-Tenant-Id: default" \
  -d '{"name": "生产"}'

# 2. 调用 NL2SQL
curl -X POST http://localhost:8082/api/data-agent/query \
  -H "Content-Type: application/json" -H "X-Api-Key: sk-xxx" \
  -d '{"question": "上月销售额"}'
```

完整接入文档：`docs/open-platform-guide.md`；SDK 示例：`docs/sdk-examples/`（Python/Java/JS）。

---

## 第四部分：测试与发布

### 测试

```bash
# 全量测试
mvn test

# 单类测试
mvn test -Dtest=SecurityGuardTest
mvn test -Dtest=TenantIsolationTest
mvn test -Dtest=ArchitectureTest

# 压测（四场景混合流量）
python scripts/loadtest/load_test.py --duration 60 --qps 5
```

### 发布流程

1. 全量测试通过（227 门禁）
2. 构建产物 `target/agent-platform-*.jar`
3. 更新版本号（pom.xml）
4. 部署 + 冒烟（四场景各 1 条）
5. 配置变更记录

---

*本手册由 Agent Platform 生成，配套使用说明书（/usage/）提供前端交互指引。*