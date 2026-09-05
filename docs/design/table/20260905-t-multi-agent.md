# t_multi_agent_run 多智能体运行表设计

> 版本：v1.0 ｜ 状态：**待检查**（2026-09-05 提交用户审批） ｜ 依据：《开发排期》W13、架构设计 `docs/design/architecture/20260905-multi-agent.md`（W13，同批待检查）、CLAUDE.md 设计文档铁律

---

## 1. 基本信息

| 项 | 值 |
|----|----|
| 表名 | t_multi_agent_run |
| 所属架构层 | L7 数据层（`com.agent.data.multiagent`） |
| 变更 | 新增表 + `t_agent_run` 加列 `parent_run_id` |
| 目标数据库 | PostgreSQL（幂等 DDL，`spring.sql.init`） |
| 状态 | 待检查 |

---

## 2. 设计目的

多智能体（W13）需要一个"根 run"记录：Supervisor/Pipeline 的拓扑、LLM 分解出的 plan、最终汇总答案、总 token 用量、与子 run 的关联。没有它：前端无法看到"一次多智能体任务的完整视图"，子 run 无法回溯归属，预算观测无落点。

**t_agent_run 加 parent_run_id**：子 Agent 是普通单 Agent run（复用 W4 一切能力），用父指针挂到根 run 下形成树——不复制一套子运行存储。

---

## 3. 字段定义（逐项附说明）

### 3.1 t_multi_agent_run（新增）

| 字段 | 类型 | 可空 | 默认 | 说明 |
|------|------|------|------|------|
| id | BIGSERIAL | NO | 自增 | 主键（=rootRunId） |
| tenant_id | VARCHAR(64) | NO | - | 租户隔离（漏此字段=越权） |
| topology | VARCHAR(16) | NO | 'supervisor' | supervisor / pipeline（Debate 预留） |
| task | TEXT | YES | NULL | 原始任务（pipeline 为 NULL，用 stages） |
| app_id | VARCHAR(64) | NO | - | Supervisor 应用（配额/角色来源） |
| stages_json | JSONB | YES | NULL | Pipeline 阶段数组（并行分支/串行阶段） |
| plan_json | JSONB | YES | NULL | Supervisor 分解结果 `[{agent,task,deps,runId,status}]` |
| status | VARCHAR(16) | NO | 'PLANNING' | PLANNING/RUNNING/WAITING_APPROVAL/COMPLETED/FAILED/CANCELLED |
| final_answer | TEXT | YES | NULL | 汇总 Agent 生成的最终结论 |
| total_token | INT | NO | 0 | 全部子任务 token 累加（metering 回填） |
| budget_limit | INT | NO | 0 | 根 run 总预算（应用 quota） |
| created_at / updated_at | TIMESTAMPTZ | NO | now() | 时间戳 |

### 3.2 t_agent_run（改：加 1 列）

| 字段 | 类型 | 变更 | 说明 |
|------|------|------|------|
| parent_run_id | BIGINT | 新增 NULL | 多智能体子 run 的根 run id；普通 run 为 NULL |

---

## 4. 索引设计

| 索引名 | 字段 | 类型 | 支撑查询 |
|--------|------|------|----------|
| uq_multi_run_id | (id) | PK | 详情 GET /runs/{id} |
| idx_multi_tenant_created | (tenant_id, created_at) | 普通 | 列表分页（租户隔离） |
| idx_agent_run_parent | (parent_run_id) | 普通 | 根 run 子 run 树查询 `WHERE parent_run_id=?` |

---

## 5. 关联关系与约束

- 逻辑外键：`t_multi_agent_run.id` ← `t_agent_run.parent_run_id`（不建物理外键，惯例）。
- 子 run 用普通 t_agent_run（复用 W4 状态机/审批/重试）；根 run 是编排层视图。

---

## 6. 租户隔离 ★必查项

| 项 | 内容 |
|----|------|
| 隔离方式 | 行级 tenant_id |
| 越权防护 | 所有查询强制 tenant_id；黑板/详情显式匹配租户；子 run 关联经 rootRunId+租户双查 |

---

## 7. 数据治理 ★必查项

| 项 | 结论 |
|----|------|
| PII | task/final_answer 可能含业务信息；与审计日志同标准（保留 90 天窗，P1 基线） |
| 血缘 | parent_run_id 树 + total_token 可回溯到 metering.log |
| 保留 | 随运行清理（同 t_agent_run）；CANCELLED 30 天后可删 |

---

## 8. 建表 SQL（批准后执行）

```sql
-- 批准状态：待检查
CREATE TABLE IF NOT EXISTS t_multi_agent_run (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   VARCHAR(64)  NOT NULL,
    topology    VARCHAR(16)  NOT NULL DEFAULT 'supervisor',
    task        TEXT,
    app_id      VARCHAR(64)  NOT NULL,
    stages_json JSONB,
    plan_json   JSONB,
    status      VARCHAR(16)  NOT NULL DEFAULT 'PLANNING',
    final_answer TEXT,
    total_token INT          NOT NULL DEFAULT 0,
    budget_limit INT         NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_multi_tenant_created ON t_multi_agent_run (tenant_id, created_at);

ALTER TABLE t_agent_run ADD COLUMN IF NOT EXISTS parent_run_id BIGINT;
CREATE INDEX IF NOT EXISTS idx_agent_run_parent ON t_agent_run (parent_run_id);
```

---

## 9. 演进预判
- 量级：多智能体任务数 << 单 Agent（每日数十~数百），行级小。
- 先撑不住：plan_json/stages_json 膨胀（大 plan）；断点续跑需求 → 黑板落库表 `t_multi_agent_board`（v2）。

---

## 10. 检查记录
| 日期 | 检查人 | 结论 | 备注 |
|------|--------|------|------|
| 2026-09-05 | 用户 | 待检查 | 与 `20260905-multi-agent.md` 一并审批 |