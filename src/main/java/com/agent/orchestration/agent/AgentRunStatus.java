package com.agent.orchestration.agent;

/**
 * L3 编排层：Agent 运行状态机（docs/design/architecture/20260831-agent-runtime.md §2.1）
 * CREATED → RUNNING → COMPLETED | TERMINATED | BUDGET_EXHAUSTED | TIMEOUT | FAILED | CANCELED
 * WAITING_APPROVAL = RUNNING 期间写操作 HITL 挂起；REJECTED = 入口校验未通过（不落主流程）
 */
public enum AgentRunStatus {
    CREATED, RUNNING, WAITING_APPROVAL,
    COMPLETED, TERMINATED, BUDGET_EXHAUSTED, TIMEOUT, FAILED, CANCELED, REJECTED
}