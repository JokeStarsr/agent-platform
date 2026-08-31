package com.agent.orchestration.workflow;

/**
 * 条件分支定义（docs/design/architecture/20260831-workflow-engine.md §2.1 CONDITION）
 */
public record ConditionBranch(String branchId, String expr, String next) {
}
