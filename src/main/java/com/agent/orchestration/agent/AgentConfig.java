package com.agent.orchestration.agent;

/**
 * L3 编排层：护栏配置（docs/design/architecture/20260831-agent-runtime.md §2.2）
 * 未显式传参时按 appId 取场景默认：CS_ 客服 / TR_ 商旅 / 其他按客服档。
 */
public record AgentConfig(int maxSteps, int tokenBudget, int timeoutMs, int loopThreshold, int maxConcurrency) {

    public static AgentConfig of(String appId) {
        if (appId != null && appId.startsWith("TR_")) {
            return new AgentConfig(25, 60_000, 180_000, 3, 10);
        }
        return new AgentConfig(10, 32_000, 60_000, 3, 5);
    }
}