package com.agent.tokenmeter;

import com.agent.data.tokenmeter.TokenUsageDailyRepository;
import com.agent.data.tokenmeter.TokenUsageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Map;

/**
 * L7 数据层：Token 计量服务（W17）
 * <p>记录每次 LLM 调用的 Token 消耗（prompt/completion/total）、费用、耗时。
 * 写入明细表（t_token_usage）+ 更新日聚合表（t_token_usage_daily）。</p>
 */
@Service
public class TokenMeterService {

    private static final Logger log = LoggerFactory.getLogger(TokenMeterService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final TokenUsageRepository tokenUsageRepo;
    private final TokenUsageDailyRepository tokenUsageDailyRepo;
    private final BudgetAlertService budgetAlertService;

    // 模型单价配置（元/千 Token）
    @Value("${token-pricing.models.claude-sonnet-4-5-20250929.prompt:0.003}")
    private BigDecimal claudePromptPrice;

    @Value("${token-pricing.models.claude-sonnet-4-5-20250929.completion:0.015}")
    private BigDecimal claudeCompletionPrice;

    @Value("${token-pricing.models.gpt-4o.prompt:0.005}")
    private BigDecimal gpt4oPromptPrice;

    @Value("${token-pricing.models.gpt-4o.completion:0.015}")
    private BigDecimal gpt4oCompletionPrice;

    @Value("${token-pricing.models.deepseek-chat.prompt:0.001}")
    private BigDecimal deepseekPromptPrice;

    @Value("${token-pricing.models.deepseek-chat.completion:0.002}")
    private BigDecimal deepseekCompletionPrice;

    public TokenMeterService(TokenUsageRepository tokenUsageRepo,
                             TokenUsageDailyRepository tokenUsageDailyRepo,
                             BudgetAlertService budgetAlertService) {
        this.tokenUsageRepo = tokenUsageRepo;
        this.tokenUsageDailyRepo = tokenUsageDailyRepo;
        this.budgetAlertService = budgetAlertService;
    }

    /**
     * 记录 Token 用量（由 LlmGateway 调用）。
     *
     * @param traceId           请求追踪 ID
     * @param tenantId          租户 ID
     * @param appId             应用 ID（可为 null）
     * @param modelName         模型名称
     * @param promptTokens      Prompt Token 数
     * @param completionTokens  Completion Token 数
     * @param totalTokens       总 Token 数
     * @param durationMs        耗时（毫秒）
     */
    public void record(String traceId, String tenantId, String appId, String modelName,
                       int promptTokens, int completionTokens, int totalTokens, long durationMs) {
        try {
            // 1. 计算费用
            BigDecimal cost = calculateCost(modelName, promptTokens, completionTokens);

            // 2. 写入明细表
            tokenUsageRepo.insert(traceId, tenantId, appId, modelName,
                    promptTokens, completionTokens, totalTokens, cost, durationMs);

            // 3. 更新日聚合表（UPSERT）
            tokenUsageDailyRepo.upsert(LocalDate.now(), tenantId, appId, modelName, totalTokens, cost);

            // 4. 检查预算告警
            budgetAlertService.check(tenantId);

            log.debug("Token 计量记录: tenant={}, model={}, tokens={}, cost={} 元",
                    tenantId, modelName, totalTokens, cost);
        } catch (Exception e) {
            // 计量失败不应影响主流程
            log.error("Token 计量记录失败: trace={}, tenant={}, err={}", traceId, tenantId, e.getMessage());
        }
    }

    /**
     * 计算费用（元）。
     */
    private BigDecimal calculateCost(String modelName, int promptTokens, int completionTokens) {
        BigDecimal promptPrice, completionPrice;

        // 根据模型名称选择单价
        if (modelName.contains("claude-sonnet-4-5")) {
            promptPrice = claudePromptPrice;
            completionPrice = claudeCompletionPrice;
        } else if (modelName.contains("gpt-4o")) {
            promptPrice = gpt4oPromptPrice;
            completionPrice = gpt4oCompletionPrice;
        } else if (modelName.contains("deepseek")) {
            promptPrice = deepseekPromptPrice;
            completionPrice = deepseekCompletionPrice;
        } else {
            // 默认单价（按 claude 计算）
            promptPrice = claudePromptPrice;
            completionPrice = claudeCompletionPrice;
            log.warn("未知模型 {}，使用默认单价", modelName);
        }

        // 计算费用：Token 数 / 1000 * 单价（元/千 Token）
        BigDecimal promptCost = BigDecimal.valueOf(promptTokens)
                .divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP)
                .multiply(promptPrice);
        BigDecimal completionCost = BigDecimal.valueOf(completionTokens)
                .divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP)
                .multiply(completionPrice);

        return promptCost.add(completionCost).setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * 获取模型单价配置（用于展示）。
     */
    public Map<String, Map<String, BigDecimal>> getModelPricing() {
        return Map.of(
                "claude-sonnet-4-5-20250929", Map.of("prompt", claudePromptPrice, "completion", claudeCompletionPrice),
                "gpt-4o", Map.of("prompt", gpt4oPromptPrice, "completion", gpt4oCompletionPrice),
                "deepseek-chat", Map.of("prompt", deepseekPromptPrice, "completion", deepseekCompletionPrice)
        );
    }
}
