package com.agent.orchestration.workflow;

import java.util.List;
import java.util.Map;

/**
 * 解析后的流程图（顶层节点扁平，PARALLEL 内部子节点已展平并记录归属）
 * 对应 docs/design/architecture/20260831-workflow-engine.md §2.1
 */
public record WorkflowGraph(
        String flowId,
        long defaultTimeoutMs,
        Map<String, NodeDef> nodes,
        List<String[]> edges,
        Map<String, List<ConditionBranch>> conditionBranches,
        Map<String, List<String>> parallelBranches,
        List<String> order // 拓扑序（含虚拟边，环检测保证正确性）
) {
}
