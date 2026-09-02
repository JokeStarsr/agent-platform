package com.agent.capability;

import java.util.Map;

/**
 * L4 能力层接口：应用 Prompt/转人工参数解析（docs/design/api/20260902-app-factory.md §2.5）
 * <p>依赖反转：RagService/ContextController 不直接依赖 L3 AppRegistry（违反七层约束），
 * 由 L3 orchestration 提供实现（OrchAppPromptProvider）。</p>
 */
public interface AppPromptProvider {

    record AppPrompt(
            String systemPrompt,
            boolean handoffEnabled,
            Double handoffThreshold,
            Map<String, Object> handoffWeights
    ) {
        /** 无应用配置时的回退（保持现有硬编码行为） */
        public static AppPrompt fallback() {
            return new AppPrompt("", false, null, null);
        }
    }

    /**
     * 按 (tenantId, appId) 解析应用 Prompt 配置；未注册应用返回 fallback。
     * 返回的 systemPrompt 已渲染（占位符已替换）。
     */
    AppPrompt resolve(String tenantId, String appId);
}
