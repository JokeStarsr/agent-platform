package com.agent.orchestration.workflow;

/**
 * 节点类型枚举（docs/design/architecture/20260831-workflow-engine.md §2.1）
 */
public enum NodeType {
    LLM, TOOL, CONDITION, HUMAN, PARALLEL, SUBFLOW
}
