package com.agent.orchestration.agent;

import java.util.Map;

/**
 * L3 编排层：Agent 每轮 LLM 决策输出（JSON 模式强约束，由 LlmGateway.generateStructured 反序列化）
 * action 三态：REASON 纯思考 / TOOL_CALL 调用工具 / FINAL_ANSWER 输出最终答案
 */
public record AgentStepIntent(String thought, String action, String tool, Map<String, Object> args, String answer) {

    public static final String REASON = "REASON";
    public static final String TOOL_CALL = "TOOL_CALL";
    public static final String FINAL_ANSWER = "FINAL_ANSWER";
}