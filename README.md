# 企业级智能体平台（agent-platform）

对齐《企业级智能体平台架构设计说明书》：七层主架构 + 双横切面。
排期与每周分工见 `../agent-dev-schedule.html`（与本目录同级的架构文档）。

## 技术栈

| 组件 | 选型 | 说明 |
|------|------|------|
| 框架 | Spring Boot 3.4.5 + Spring AI 1.0.0 | 与本地 springAITest 验证版本一致 |
| JDK | 17 | 如需 21 可改 pom.properties.java.version |
| LLM | DeepSeek（OpenAI 兼容协议） | 日常 deepseek-chat，难题 deepseek-reasoner |
| Embedding | 智谱 embedding-2 | RAG 向量化 |
| 向量库 | PostgreSQL + pgvector | 本地开发，Milvus 留 P3 前切换 |
| 缓存 | Redis | 短期记忆（P2 启用） |

## 快速开始

### 1. 起基础设施

```bash
docker compose up -d
```

### 2. 配置 API Key

二选一，设置环境变量 `DEEPSEEK_API_KEY`：

- **DeepSeek 官方**：`https://api.deepseek.com`（application.yml 默认）
- **sub2api 网关**（本地 8080 网关方案）：改 application.yml 中
  `spring.ai.openai.base-url: http://localhost:8180/v1`

### 3. 用 IDEA 打开

IDEA → Open → 选择 `D:\ClaudeCode\AgentProduct\agent-platform\pom.xml`。
首次打开会自动导入 Maven 依赖（需联网拉取 spring-ai-bom 等）。

### 4. 运行

启动 `com.agent.AgentPlatformApplication`，验证演示接口：

```bash
# 单轮问答
curl -X POST http://localhost:8080/api/chat/ask \
  -H "Content-Type: application/json" \
  -d '{"message":"用一句话介绍智能体平台"}'

# 流式（SSE）
curl -X POST http://localhost:8080/api/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"message":"介绍一下 Spring AI"}'
```

## 目录结构（七层）

```
com.agent
├── access          # L1 接入层：网关、认证、限流、审计（待建）
├── app             # L2 应用层：四大场景 + chat 示例
├── orchestration   # L3 编排层：Agent Runtime / Workflow（待建）
├── capability      # L4 能力层：RAG / Prompt / 记忆 / 上下文（待建）
├── tool            # L5 工具协议层：MCP 网关 / Function Call / 沙箱（待建）
├── model           # L6 模型服务层：LLM 网关（已建 v1）
└── data            # L7 数据层：文档管道 / 存储访问（待建）
common              # 基础设施：Result / 异常 / trace_id（不属于任何业务层）
```

依赖方向：**只允许向下**。应用层禁止直连模型与数据库；工具一律走协议层。

## 重要约定

- **设计文档铁律**：任何表结构 / 接口契约 / 架构变更，先写设计文档到
  `docs/design/`，经用户检查通过后才实现。规范见 `docs/DESIGN-DOCUMENT-POLICY.md`。
- **模型路由**：日常编码用 deepseek-chat，难题用 deepseek-reasoner。
  在 IDEA 运行配置里改 `spring.ai.openai.chat.options.model` 即可切换。
- **会话纪律**：一次会话只做一个可验证的小任务，完成即 commit，跑偏即回退。