package com.agent.orchestration.agent;

/**
 * L3 编排层：护栏配置（docs/design/architecture/20260831-agent-runtime.md §2.2）
 * 未显式传参时按 appId 取场景默认：CS_ 客服 / TR_ 商旅 / 其他按客服档。
 */
public record AgentConfig(int maxSteps, int tokenBudget, int timeoutMs, int loopThreshold, int maxConcurrency) {

    public static AgentConfig of(String appId) {
        if (appId != null && appId.startsWith("TR_")) {
            return new AgentConfig(25, 60_000, 300_000, 3, 10);
        }
        // timeout 300s：实测 LLM 通道单轮决策 5-45s（sub2api→zen 免费通道），10 步 × 单轮 + 余量
        return new AgentConfig(10, 32_000, 300_000, 3, 5);
    }
}