package com.agent.orchestration.workflow;

/**
 * 实例状态枚举（docs/design/architecture/20260831-workflow-engine.md §2.2）
 */
public enum WorkflowStatus {
    CREATED, RUNNING, WAITING_APPROVAL, COMPLETED, FAILED, CANCELED, REJECTED
}
