# 安全加固与租户隔离设计（L4 能力层 · W20-W21 缓冲期红线）

> 版本：v1.0 ｜ 状态：**已实现**（2026-09-11，227 测试全绿，注入拦截率 100%，越权矩阵全拦截） ｜ 依据：《开发排期》W20（提示词注入防护与内容安全）、W21（租户隔离加固与越权压测）、CLAUDE.md 设计文档铁律

---

## 1. 设计目的

**要解决的问题**：平台进入上线评估阶段，需补齐企业必需的安全横切面：
1. **Prompt 注入**——用户输入中的"忽略系统指令/角色扮演/要求泄露 System Prompt"攻击；
2. **PII 泄露**——LLM 输入/输出中的身份证/银行卡/手机号/密钥等敏感信息；
3. **租户越权**——跨租户访问数据/工具/运行实例。

**不做会怎样**：注入攻击可让 LLM 绕过护栏执行危险操作；PII 泄露违反合规；租户越权导致数据泄露事故。

**范围**：本设计实现 **L4 安全防护 v1**——①Prompt 注入检测器（模式规则 17 条 + LLM 语义补充）；②PII 识别脱敏（身份证/银行卡/手机/邮箱/IP/密钥）；③安全 API；④租户隔离越权测试套件（4 类 × 13 条代表用例）。**不做**：LLM 输出强制过滤接入（独立 API，需选型接入点）、删除级联（W21 军令状后做）、OAuth2 认证（W22+）。

---

## 2. 关键架构决策（ADR-20260911-01：模式规则优先 + deny-by-default）

### 2.1 注入检测：模式规则（确定性）优先，LLM 语义补充

```
用户输入 ─► PromptInjectionDetector（L4）
   ├─ 模式规则 17 条（毫秒级，命中即拦截"宁可误拦不可漏判"）
   │    ├─ direct_ignore：忽略/无视/跳过系统指令
   │    ├─ prompt_leak：要求输出/重复 System Prompt
   │    ├─ role_play：扮演管理员/OpenAI/开发者
   │    ├─ document_poison：文档内容投毒
   │    ├─ tool_result_poison：工具返回值投毒
   │    └─ danger_word：SQL 破坏/密钥读取组合
   └─ LLM 语义通道（规则未命中时，输出 INJECTED/SAFE 二选一）
```

- **为什么模式规则优先**：确定性、毫秒级、无 LLM 成本、易审计。LLM 语义检测慢且贵，只作补充通道。
- **为什么宁可误拦**：注入是红线领域——漏判一条 = 平台被攻陷。测试样本集 18/18 拦截（100%）。

### 2.2 PII 脱敏：正则识别 + 三态输出

```
文本 ─► PiiMasker（L4）
   ├─ 识别：身份证(18位) / 银行卡(62开头银联/4/5/6) / 手机(11位) / 邮箱 / IPv4 / 密钥(password=)
   ├─ mask()：输出全脱敏（***）+ findings 保留部分可见（138****5678）
   └─ containsPii()：布尔判断（防误报普通文本）
```

### 2.3 租户越权：deny-by-default + 归属校验哨兵

```
跨租户攻击 ─► 隔离机制
   ├─ AgentRuntime：checkTenant → 403
   ├─ MemoryService：tenant+user 归属校验 → 403
   ├─ WorkflowService：tenant 匹配校验 → 403
   ├─ McpAuthorization：deny-by-default → 403
   └─ OpenPlatform：API Key 租户绑定 → 拒绝
```

- 四类攻击（跨租户检索/记忆越权/工具越权/会话劫持）全部测试固化（13 条代表用例）。

---

## 3. API 设计

```
POST /api/security/injection/detect   {text, source} → {blocked, category, message}
POST /api/security/pii/mask           {text} → {maskedText, findings[]}
POST /api/security/pii/check          {text} → {containsPii}
GET  /api/security/injection/logs     [最近 500 条检测日志]
GET  /api/security/injection/rules    [规则列表]
```

---

## 4. 测试与验收

| 项 | 目标 | 实测 |
|----|------|------|
| 注入样本集 | 拦截率 ≥95% | ✅ 18/18（100%） |
| 正常输入误拦 | 0% | ✅ 10 条正常输入全放行 |
| PII 脱敏 | 抽样全对 | ✅ 11 条全识别 + 脱敏 |
| 越权矩阵 | 全拦截 | ✅ 13 条代表用例（4 类） |

---

## 5. 依赖

| 依赖 | 状态 |
|------|------|
| L6 LlmGateway | ✅（LLM 语义检测通道） |
| 服务归属校验 | ✅ 已有（AgentRun/Workflow/Memory/授权表） |

---

**已实现并推送，缓冲期红线达成。**