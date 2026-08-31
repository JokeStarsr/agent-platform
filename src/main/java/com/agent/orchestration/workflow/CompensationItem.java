package com.agent.orchestration.workflow;

import java.util.Map;

/**
 * 补偿清单条目（t_workflow_instance.compensation 元素，docs/design/architecture/20260831-workflow-engine.md §2.6）
 * 记录已完成写节点：回滚时逆序调用 rollbackTool，幂等键复用原键（防重复补偿）。
 */
public record CompensationItem(
        String nodeId,
        String tool,
        Map<String, Object> args,
        String rollbackTool,
        String idempotencyKey,
        String compensatedAt // null=待补偿；非 null=已回滚时间 ISO
) {
}
