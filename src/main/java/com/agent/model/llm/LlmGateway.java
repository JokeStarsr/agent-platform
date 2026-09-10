package com.agent.model.llm;

import com.agent.tokenmeter.TokenMeterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.UUID;

/**
 * LLM 网关（L6 模型服务层）
 * <p>统一经本网关访问模型（架构铁律：应用层禁止直连模型）。
 * system 为空时跳过 .system()（Spring AI 1.0 的 Assert.hasText 拒绝空 system 字符串）。
 * ChatClient builder 不可变，链式调用每个方法都返回新 spec。
 * W17：集成 Token 计量（TokenMeterService），记录每次调用的 Token 消耗和费用。</p>
 */
@Service
public class LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(LlmGateway.class);

    private final ChatModel chatModel;
    private final TokenMeterService tokenMeterService;

    // Token 估算比例（字符数 → Token 数）
    private static final double CHARS_PER_TOKEN_EN = 4.0;   // 英文：4 字符 ≈ 1 Token
    private static final double CHARS_PER_TOKEN_ZH = 1.5;   // 中文：1.5 字符 ≈ 1 Token

    public LlmGateway(ChatModel chatModel, TokenMeterService tokenMeterService) {
        this.chatModel = chatModel;
        this.tokenMeterService = tokenMeterService;
    }

    /** 单轮生成 */
    public String generate(String system, String user) {
        long startTime = System.currentTimeMillis();
        ChatClient.ChatClientRequestSpec spec = applySystem(spec().user(user), system);
        String result = spec.call().content();
        long durationMs = System.currentTimeMillis() - startTime;

        // Token 计量（估算）
        recordTokenUsage(null, null, getModelName(), system, user, result, durationMs);

        return result;
    }

    /** 单轮生成（指定模型名覆盖默认配置，用于 LLM-as-judge 双模型互判等场景） */
    public String generateWithModel(String system, String user, String model) {
        long startTime = System.currentTimeMillis();
        ChatClient.ChatClientRequestSpec spec = applySystem(spec().user(user), system);
        if (model != null && !model.isBlank()) {
            spec = spec.options(OpenAiChatOptions.builder().model(model).build());
        }
        String result = spec.call().content();
        long durationMs = System.currentTimeMillis() - startTime;

        // Token 计量（估算）
        recordTokenUsage(null, null, model, system, user, result, durationMs);

        return result;
    }

    /** 流式生成 */
    public Flux<String> stream(String system, String user) {
        ChatClient.ChatClientRequestSpec spec = applySystem(spec().user(user), system);
        return spec.stream().content();
    }

    /** 结构化输出（JSON 模式强约束）：LLM 返回指定类型，用于 Agent 决策等需机器可读的场景 */
    public <T> T generateStructured(String system, String user, Class<T> outputType) {
        ChatClient.ChatClientRequestSpec spec = applySystem(spec().user(user), system);
        return spec.call().entity(outputType);
    }

    private ChatClient.ChatClientRequestSpec spec() {
        return ChatClient.builder(chatModel).build().prompt();
    }

    private ChatClient.ChatClientRequestSpec applySystem(ChatClient.ChatClientRequestSpec spec, String system) {
        return (system != null && !system.isBlank()) ? spec.system(system) : spec;
    }

    /** 获取当前模型名称 */
    private String getModelName() {
        // TODO: 从 ChatModel 配置中读取实际模型名
        return "claude-sonnet-4-5-20250929";
    }

    /** 记录 Token 用量（估算） */
    private void recordTokenUsage(String traceId, String tenantId, String modelName,
                                  String system, String user, String result, long durationMs) {
        try {
            // 估算 Prompt Token 数（system + user）
            String prompt = (system != null ? system : "") + (user != null ? user : "");
            int promptTokens = estimateTokens(prompt);

            // 估算 Completion Token 数（result）
            int completionTokens = estimateTokens(result);

            // 总 Token 数
            int totalTokens = promptTokens + completionTokens;

            // 生成 traceId（如果没有）
            if (traceId == null || traceId.isBlank()) {
                traceId = UUID.randomUUID().toString();
            }

            // 租户 ID（默认 "default"）
            if (tenantId == null || tenantId.isBlank()) {
                tenantId = "default";
            }

            // 记录 Token 用量
            tokenMeterService.record(traceId, tenantId, null, modelName,
                    promptTokens, completionTokens, totalTokens, durationMs);
        } catch (Exception e) {
            // 计量失败不应影响主流程
            log.warn("Token 计量记录失败: {}", e.getMessage());
        }
    }

    /** 估算文本的 Token 数（基于字符数） */
    private int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }

        // 检测是否包含中文
        boolean hasChinese = text.matches(".*[\\u4e00-\\u9fa5].*");
        double charsPerToken = hasChinese ? CHARS_PER_TOKEN_ZH : CHARS_PER_TOKEN_EN;

        return (int) Math.ceil(text.length() / charsPerToken);
    }
}