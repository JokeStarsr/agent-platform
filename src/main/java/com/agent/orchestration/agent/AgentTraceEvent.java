package com.agent.orchestration.agent;

import java.time.Instant;

/**
 * L3 编排层：trace 事件（步步留痕，落 t_agent_step + SSE 推送）
 * phase: PLAN / REFLECT / TOOL_CALL / TOOL_RESULT / HITL
 * seqNo: run 内全局递增事件序号（同一 LLM 步内多次 emit 各占唯一序号，回放顺序 = seqNo 升序）
 */
public record AgentTraceEvent(
        String phase,
        int seqNo,
        String tool,
        String argsHash,
        String resultHash,
        int llmTokens,
        long latencyMs,
        String decision,
        Instant ts) {
}