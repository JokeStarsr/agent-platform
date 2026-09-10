package com.agent.model.llm;

/**
 * L6 模型层：Token 计量接口（W17）
 * <p>定义 Token 计量契约，由 App 层 TokenMeterService 实现。
 * 使用依赖反转避免 Model 层直接依赖 App 层。</p>
 */
public interface TokenMeter {

    /**
     * 记录 Token 用量。
     *
     * @param traceId          请求追踪 ID
     * @param tenantId         租户 ID
     * @param appId            应用 ID（可为 null）
     * @param modelName        模型名称
     * @param promptTokens     Prompt Token 数
     * @param completionTokens Completion Token 数
     * @param totalTokens      总 Token 数
     * @param durationMs       耗时（毫秒）
     */
    void record(String traceId, String tenantId, String appId, String modelName,
                int promptTokens, int completionTokens, int totalTokens, long durationMs);
}
