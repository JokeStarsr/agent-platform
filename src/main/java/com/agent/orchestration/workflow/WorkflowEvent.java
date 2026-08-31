package com.agent.orchestration.workflow;

import java.time.Instant;

/**
 * Workflow 实时事件（SSE 推送，docs/design/architecture/20260831-workflow-engine.md §3）
 * phase: FLOW_START / NODE_START / NODE_DONE / HUMAN_WAIT / HUMAN_RESULT / COMPENSATING / FLOW_END
 */
public record WorkflowEvent(
        String phase,
        long instanceId,
        String nodeId,
        String status,
        String message,
        Instant ts) {
}
