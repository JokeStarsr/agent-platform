# 转人工机制设计（置信度门控 + 转人工标记）

> 版本：v1.1 ｜ 状态：**已批准** (2026-08-30 用户审批通过，实现完成，待完整评测确认) ｜ 依据：《架构设计说明书》5.2 节 RAG 双流水线、P1 闸门（转人工率 ≤ 30%）、《开发排期》W3；扩展 `docs/design/api/20260829-rag-controller.md` §2.2 的 search 响应契约

---

## 1. 设计目的

**要解决的问题**：P1 闸门要求"转人工率 ≤ 30%"，但当前服务端没有任何可度量的置信度与转人工机制：

1. 评测脚本 `golden_set_runner.py` 的转人工判定是**文本猜测**（正则找"我不知道/转人工"字样），不是服务端真实信号——服务端不可信，闸门指标形同虚设。
2. 客服前端（W3 交付物）将来需要"转人工按钮"，但服务端没有"该不该转"的答案，前端无从判断。
3. Prompt 里虽有"低置信度转人工"的软性要求，但模型遵守与否无法量化、无法校准。

**不做会怎样**：转人工率闸门永远无法真正测量，P1 收口不闭环；前端转人工按钮只能是摆设。

---

## 2. 设计细节

### 2.1 置信度评分（v1：纯服务端启发式，零额外 LLM 调用）

**设计约束**：P1 还有"首 Token ≤ 2s"闸门，置信度计算**不得新增 LLM 调用**（避免双倍延迟/成本）。只用 RAG 管道已有产物（检索分数、Top-K 内容、生成答案、引用列表）合成。

置信度 = 加权合成，权重可配置：

```
confidenceScore = w_retrieval × retrievalScore
                + w_coverage  × coverageScore
                + w_citation  × citationScore
```

| 分量 | 定义 | 默认权重 | 说明 |
|------|------|---------|------|
| `retrievalScore` | Top-K 命中切片的最大相似度（PGVector cosine，0-1） | 0.55 | 检索越强，答案越可能站得住 |
| `coverageScore` | 用户问题关键词与 Top-1 切片的 bigram 重叠率（0-1） | 0.25 | 防止"检索到但答非所问" |
| `citationScore` | 答案是否带引用编号【n】（1.0 / 0.0） | 0.20 | 带引用的答案更可能 grounded |

> **校准说明（2026-08-30 三轮实测校准，覆盖 92 条含 12 刁钻）**：PGVector 相似度实测偏低（相关命中约 0.2-0.4，非 0.5-0.7），纯检索分区分度不够，故引入 coverage + citation 兜底。实测校准结论：
> - **拒答信号 = 转人工主信号**。模型对刁钻题几乎都主动拒答（"我不知道/无法回答/无法确认/没有找到/并未包含任何"+ 内容安全"拒绝生成/无法提供任何"）。信号必须**排除**：①"建议转人工/请转人工"（好答案的常见收尾语）；②"无法提供"（好答案对子部分的缺口说明，如"无法提供该部分信息"）。全量拒答用"无法提供**任何**"（含"任何"才触发拒答）。
> - **阈值 0.50→0.40→0.25**：三轮后刁钻题全改由 REFUSAL 信号识别（不再依赖 LOW_CONFIDENCE），LOW_CONFIDENCE 降为纯兜底，阈值放低到 0.25 以减少 KB 可答题的误转人工。权重保持 0.55/0.25/0.20 未调。
> - **量纲对齐**：Golden Set 的 `confidenceFloor` 原 0.8 是旧量纲，与合成置信度（KB 好答案实测 0.4-0.77）不匹配，已对齐为与阈值一致（0.25）——详见 `docs/design/eval/20260827-golden-set.md` 变更历史。

### 2.2 转人工判定规则（分级原因）

按优先级从高到低：

| 优先级 | 条件 | `handoffReason` | 说明 |
|--------|------|-----------------|------|
| 1 | 查询命中有害意图词（黑掉/入侵系统/盗取/勒索/破解密码/制作病毒/攻击网站/诈骗） | `SENSITIVE` | 内容安全升级（2026-08-30 补）：不依赖模型逐次拒答行为，强制转人工 |
| 2 | 检索结果为空（Top-K = 0） | `NO_RETRIEVAL` | 库里没东西，直接转人工 |
| 3 | 答案文本带拒答信号（"我不知道"/"无法回答"/"没有找到"/"拒绝生成"等） | `REFUSAL` | 模型主动拒答 = 它判断自己答不了 |
| 4 | `confidenceScore < handoffThreshold`（默认 0.25） | `LOW_CONFIDENCE` | 合成置信度跌破阈值 |
| 5 | 以上都不满足 | `NONE` | 正常作答，不转人工 |

```
needsHandoff = (NO_RETRIEVAL) OR (REFUSAL) OR (LOW_CONFIDENCE)
```

**拒答覆盖规则**：若命中 `REFUSAL`，即使合成置信度偏高也强制转人工——模型明确表示答不了，就是最可靠的转人工信号。

### 2.3 响应契约扩展（/api/rag/search）

在 `Result<RagResult>` 的 `data` 上新增 3 个字段，**向后兼容**（现有字段不动，评测脚本对未知字段自动忽略）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `data.confidenceScore` | double (0-1) | 合成置信度 |
| `data.needsHandoff` | boolean | 是否建议转人工（前端据此点亮"转人工"按钮） |
| `data.handoffReason` | string | `NONE` / `NO_RETRIEVAL` / `REFUSAL` / `LOW_CONFIDENCE` |

**不在本次范围（明确延后）**：转人工票据落库接口（`POST /api/rag/handoff`）与 `t_handoff` 表。理由：票据落库只在有人工坐席/客服前端消费时才有意义，属 W3 前端 + 审计日志（收口第 4 项）的范围；本次先把"是否该转"这一信号做对。届时建表走 `docs/templates/TABLE-DESIGN-TEMPLATE.md` 单独设计。

### 2.4 配置项（application.yml）

```yaml
app:
  rag:
    enabled: true
    handoff:
      threshold: 0.50        # 合成置信度阈值，低于则转人工
      w-retrieval: 0.55      # 检索分量权重
      w-coverage: 0.25       # 覆盖分量权重
      w-citation: 0.20       # 引用分量权重
```

权重与阈值全部可配，评测校准时只改配置不动代码。

### 2.5 评测口径变更（golden_set_runner.py）

评测脚本从"文本猜转人工"升级为**消费服务端真实信号**：

| 指标 | 新口径 | 目标 |
|------|--------|------|
| 转人工正确率 | `shouldAnswer=false` 的条目 `needsHandoff=true`；`shouldAnswer=true` 的条目 `needsHandoff=false` | 100% |
| 转人工率 | `count(needsHandoff=true) / total` | ≤ 30% |
| 置信度下限 | `shouldAnswer=true` 的条目 `confidenceScore ≥ expect.confidenceFloor` | 100% |

runner 从 `/api/rag/search` 响应读取 `data.confidenceScore / needsHandoff / handoffReason`，替换现有 §185-192 行的文本猜测逻辑。`confidenceFloor`（Golden Set 本就定义了但一直没用上）这次真正参与判分。

---

## 3. 备选方案与放弃理由

| 方案 | 结论 | 理由 |
|------|------|------|
| **纯启发式合成（本文采用）** | ✅ | 零额外 LLM 调用，不增延迟（保首 Token ≤ 2s）；确定性强，评测可复现；信号全部来自管道已有产物 |
| 生成后 LLM-as-judge 打置信度 | ❌ 放弃 | 每次问答多一次 LLM 调用：延迟翻倍、token 成本翻倍，与首 Token ≤ 2s 闸门冲突；且与评测的 faithfulness judge 职责重叠。P2 可作"校验增强"，不作为门控主信号 |
| Prompt 要求模型自报置信度（如末尾输出 `CONFIDENCE: 0.85`） | ❌ 放弃 | 模型自评不可靠（系统性偏高），解析失败需兜底；同一模型既作答又自评，无法识别"自信地答错" |
| 直接建 `t_handoff` 票据表 + 落库接口 | ❌ 本期放弃 | 无人坐席消费前是空转成本；票据字段（处理人/状态/结论）现在拍脑袋，等前端+审计日志一起设计更准 |

---

## 4. 合规/租户/血缘/保留策略影响

- **租户隔离**：无新增存储，不涉及租户数据；`needsHandoff`/`confidenceScore` 为每租户查询的响应属性，随现有 `X-Tenant-Id` 链路透传，无跨租户风险。
- **数据血缘**：转人工原因 `handoffReason` 连同 citations/sourceChunks 一起返回，可回溯"为什么转人工"（没检索到 / 模型拒答 / 置信度低），badcase 分析链路完整。
- **PII 脱敏**：不新增数据；confidence 计算只看检索分数与答案文本，不触碰个人数据。
- **保留策略**：不新增存储，无保留策略变更；转人工票据的保留规则在 `t_handoff` 表设计时（P2）一并定义。

---

## 5. 演进与限制（哪里先撑不住）

- **启发式信号的上限**：检索分+文本信号合成的置信度，对"自信地答错"（模型一本正经给出错误答案）识别无能——那是 LLM-as-judge 的职责，P2 作为增强补上。
- **权重校准依赖 Golden Set 质量**：校准效果受评测集刁钻/边界题分布影响；Golden Set 扩到 200 条后需重校。
- **阈值是全局单一值**：不同业务分类（政策类 vs 物流类）对置信度的敏感度可能不同，未来可按分类设阈值（配置项已预留扩展位）。

---

## 6. 实施清单（文档通过后）

1. `RagService`：新增置信度合成 + 转人工判定私有方法；`RagResult` 加 3 字段；`generateWithCitations` 组装时计算
2. `application.yml`：新增 `app.rag.handoff.*` 配置块
3. `golden_set_runner.py`：转人工判定改为消费 `needsHandoff/confidenceScore`，报告新增"转人工率/转人工正确率"概览
4. 跑 Golden Set 校准权重与阈值（目标：转人工正确率 100%、转人工率 ≤ 30%），结果回写本文档 §2.1
5. 回写 `docs/design/api/20260829-rag-controller.md` §2.2 响应表，标注 3 个新增字段并指向本文档

---

## 7. 变更历史

| 版本 | 日期 | 变更 | 说明 |
|------|------|------|------|
| v1.0 | 2026-08-30 | 初版 | 定义置信度合成公式、转人工判定规则、search 响应契约扩展、评测口径升级 |
