# 项目改动记录（CHANGELOG）

> 记录本迭代周期的功能变更、环境调整与开发决策。按日期倒序。

---

## 2026-08-29（P1 链路打穿：RAG 全链路打通）

### 功能与代码

- **RagService 补齐 delete 方法**：按租户清空向量切片（`FilterExpressionBuilder.eq("tenant_id", tenantId)`），供评测重置使用
- **RagController 新增**（L4 能力层 REST 接口，对应设计文档 `docs/design/api/20260829-rag-controller.md`）：
  - `POST /api/rag/search`：在线检索全管道（查询改写→混合检索→重排→Top-K→生成+引用溯源）
  - `POST /api/rag/index`：文档入库（Tika 解析→切块→向量化→写租户 Collection）
  - `DELETE /api/rag/collections`：按租户清空知识库
  - 全部读取 `X-Tenant-Id` 请求头做租户隔离
- **RagService 接入真实 LlmGateway**：替换占位符实现，生成阶段走 DeepSeek（OpenAI 兼容协议），遵循拒答/引用/置信度 Prompt 规则
- **评测脚本 `golden_set_runner.py` 重写**：
  - 调用真实 RAG 接口 `/api/rag/search`（原 `/api/chat/ask`）
  - 中文友好：字符 bigram Jaccard 相似度替代空格分词（中文无空格）
  - 真实 Recall@5：标准答案核心片段 vs 检索切片匹配
  - 修复 `--json` 参数解析 bug

### Golden Set 评测集

- **80 条完整生成**（`src/test/resources/golden-set/v1/golden-set.json`）：
  - 分类：退换货 16 / 物流 18 / 支付 21 / 会员 15 / 其他 10
  - 难度：easy 28 / medium 27 / hard 25
  - 每条含 `question` + `expect{answer, answerSource, shouldAnswer, confidenceFloor}`
- **种子知识库文档 3 份**（`golden-set/v1/documents/`）：tc_policy_v3.md（退换货/物流）、tc_payment_v1.md（支付）、tc_member_v1.md（会员）
- 首次评测结果：80 条中 79 未达标——暴露**知识库覆盖不足**（仅 3 文档 8 切片）与**忠实度 bigram 近似算法过严**，属迭代基线而非链路故障

### 环境与配置

- **ZHIPU_API_KEY 配置**：`application.yml` 中 `app.rag.enabled=true`、`model.embedding=zhipuai`、zhipuai api-key 填入（环境变量兜底）
- **pgvector 环境**：Docker 镜像改走 daocloud 国内源（`docker.m.daocloud.io/pgvector/pgvector:pg16`），容器 `agent-platform-pg` 运行中，vector 扩展 0.8.6
- **agent-platform 启动**：`mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8082`（8080 被 opencode2api 占用）
- **sub2api 优先级调整**：aliyun 优先（account_groups.priority=1）、deepseek 第二（=2）、zen-free 最后（=3/10）；aliyun concurrency 10→2

### 诊断记录

- **token 走 deepseek 不走 aliyun**：aliyun 上游（阿里云 MaaS）持续返回 429 Too Many Requests（账号限流/配额），sub2api 自动降级 deepseek 兜底。优先级配置正确，属账号侧限流问题
- **ChatModel 双 bean 冲突**：Spring AI 1.0.0 模型选择器机制，需显式配置 `spring.ai.model.chat/embedding/image`

### 已知待办

- [ ] 扩充知识库（80 条问题对应的完整业务文档）
- [ ] 忠实度评测改用 LLM-as-judge（当前 bigram 近似过严）
- [ ] 评测指标校准后接入 CI 门禁
- [ ] aliyun 上游 429 需在阿里云控制台确认配额
