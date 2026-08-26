# agent-platform 项目铁律

## 架构约束（违反即拒绝合并）

- 七层：access → app → orchestration → capability → tool → model → data，依赖只允许向下
- 禁止：应用层直连模型或数据库；工具绕过模型层/协议层直接调用 LLM
- 所有写操作（工具调用）必须携带幂等键 idempotency_key
- 所有对外调用必须透传 trace_id 并记录 Token 用量（日志经 MDC 自动带 trace_id）
- 统一响应体 Result&lt;T&gt;，异常走 GlobalExceptionHandler，禁止裸抛
- Prompt 不得硬编码在业务代码中（P2 起由 Prompt 中心管理）

## 模型路由

- 日常编码 / 生成 / 测试：`deepseek-chat`
- 架构设计 / 疑难 Bug / 重构方案：`deepseek-reasoner`
- 切换方式：IDEA 运行配置修改 `spring.ai.openai.chat.options.model`

## 设计文档铁律（用户强制要求）

**任何重要设计必须先写设计文档、经用户检查通过后才能写代码实现。**

- 范围：新建/修改表结构、接口契约、架构决策、安全规则、数据治理变更
- 位置：本仓库 `docs/design/`（表结构用 `docs/templates/TABLE-DESIGN-TEMPLATE.md`）
- 流程：写设计文档 → 用户检查 → 批准后实现 → 实现偏离设计时回写文档
- 治理细则见 `docs/DESIGN-DOCUMENT-POLICY.md`

## 测试纪律

- 单测 + 契约测试：每个服务接口配 interface + impl + 契约测试
- 集成测试（真实 AI API / 网络）用 `@Tag("integration")`，默认不跑，避免花钱