package com.agent.orchestration.workflow;

/**
 * 节点执行状态枚举（docs/design/architecture/20260831-workflow-engine.md §2.2 / §4.2）
 * 人工节点超时升级用 escalated_at 时间戳表达（节点状态保持 WAITING_APPROVAL）
 */
public enum NodeStatus {
    PENDING, RUNNING, WAITING_APPROVAL, COMPLETED, FAILED, SKIPPED, CANCELED
}
