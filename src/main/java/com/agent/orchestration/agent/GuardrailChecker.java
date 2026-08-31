package com.agent.orchestration.agent;

/**
 * L3 编排层：四重熔断检查（无状态，可单测）
 * maxSteps / tokenBudget / timeout 三闸门 + 循环检测（由 Runtime 统计相似动作序列后调用 loopTripped）
 */
public final class GuardrailChecker {

    public enum Verdict { OK, MAX_STEPS, BUDGET_EXHAUSTED, TIMEOUT }

    private GuardrailChecker() {
    }

    /** 每轮循环开始前调用，返回 OK 或对应的终止判定 */
    public static Verdict check(int stepsDone, int maxSteps, int tokensUsed, int tokenBudget,
                                long elapsedMs, long timeoutMs) {
        if (stepsDone >= maxSteps) {
            return Verdict.MAX_STEPS;
        }
        if (tokensUsed >= tokenBudget) {
            return Verdict.BUDGET_EXHAUSTED;
        }
        if (elapsedMs >= timeoutMs) {
            return Verdict.TIMEOUT;
        }
        return Verdict.OK;
    }
}