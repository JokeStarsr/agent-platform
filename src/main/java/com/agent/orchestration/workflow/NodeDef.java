package com.agent.orchestration.workflow;

import java.util.List;
import java.util.Map;

/**
 * 节点定义（扁平结构；PARALLEL 内部分支已展平为独立 NodeDef，parentNodeId 标识归属）
 * 对应 docs/design/architecture/20260831-workflow-engine.md §2.1
 */
public record NodeDef(
        String id,
        NodeType type,
        // TOOL
        String tool,
        Map<String, Object> args,
        String out,
        String rollbackTool,
        // LLM
        String prompt,
        // HUMAN
        Long escalateAfterMs,
        // PARALLEL
        List<NodeDef> parallelBranches,
        String aggregate,
        // CONDITION
        List<ConditionBranch> conditionBranches,
        // SUBFLOW（v1 stub）
        String flowRef,
        // 层级关系（非顶层节点才有，嵌套时填 parentId）
        String parentNodeId,
        // HUMAN
        String title,
        String content
) {
}
