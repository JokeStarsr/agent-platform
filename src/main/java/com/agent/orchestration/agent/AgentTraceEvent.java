package com.agent.orchestration.agent;

import java.time.Instant;

/**
 * L3 编排层：trace 事件（步步留痕，落 t_agent_step + SSE 推送）
 * phase: PLAN / REFLECT / TOOL_CALL / TOOL_RESULT / HITL
 */
public record AgentTraceEvent(
        String phase,
        int stepNo,
        String tool,
        String argsHash,
        String resultHash,
        int llmTokens,
        long latencyMs,
        String decision,
        Instant ts) {
}