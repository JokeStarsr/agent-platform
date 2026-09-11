package com.agent.model.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.UUID;

/**
 * LLM 网关（L6 模型服务层）
 * <p>统一经本网关访问模型（架构铁律：应用层禁止直连模型）。
 * system 为空时跳过 .system()（Spring AI 1.0 的 Assert.hasText 拒绝空 system 字符串）。
 * ChatClient builder 不可变，链式调用每个方法都返回新 spec。
 * W17：集成 Token 计量（TokenMeter 接口），记录每次调用的 Token 消耗和费用。
 * 通过依赖反转避免 Model 层直接依赖 App 层。</p>
 */
@Service
public class LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(LlmGateway.class);

    private final ChatModel chatModel;
    private final TokenMeter tokenMeter;

    // Token 估算比例（字符数 → Token 数）
    private static final double CHARS_PER_TOKEN_EN = 4.0;   // 英文：4 字符 ≈ 1 Token
    private static final double CHARS_PER_TOKEN_ZH = 1.5;   // 中文：1.5 字符 ≈ 1 Token

    public LlmGateway(ChatModel chatModel, TokenMeter tokenMeter) {
        this.chatModel = chatModel;
        this.tokenMeter = tokenMeter;
    }

    /** 单轮生成 */
    public String generate(String system, String user) {
        return generateWithUsage(system, user).content();
    }

    /** 单轮生成（返回真实 usage） */
    public ChatResponse generateWithUsage(String system, String user) {
        long startTime = System.currentTimeMillis();
        ChatClient.ChatClientRequestSpec spec = applySystem(spec().user(user), system);
        ChatResponse response = spec.call();
        String result = response.content();
        long durationMs = System.currentTimeMillis() - startTime;

        // Token 计量（真实 usage）
        recordTokenUsage(null, null, getModelName(), system, user, result, durationMs, response);

        return response;
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

    /** 流式生成（返回真实 usage） */
    public Flux<ChatResponse> streamWithUsage(String system, String user) {
        ChatClient.ChatClientRequestSpec spec = applySystem(spec().user(user), system);
        return spec.stream();
    }

    /** 结构化输出（JSON 模式强约束）：LLM 返回指定类型，用于 Agent 决策等需机器可读的场景 */
    public <T> T generateStructured(String system, String user, Class<T> outputType) {
        return generateStructuredWithUsage(system, user, outputType).entity(outputType);
    }

    /** 结构化输出（真实 usage） */
    public <T> ChatResponse generateStructuredWithUsage(String system, String user, Class<T> outputType) {
        long startTime = System.currentTimeMillis();
        ChatClient.ChatClientRequestSpec spec = applySystem(spec().user(user), system);
        ChatResponse response = spec.call();
        long durationMs = System.currentTimeMillis() - startTime;

        // 提取结果内容用于计量
        T result = response.entity(outputType);

        // Token 计量（真实 usage）
        String content = response.content();
        recordTokenUsage(null, null, getModelName(), system, user, content, durationMs, response);

        return response;
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

    /** 记录 Token 用量（真实 usage） */
    private void recordTokenUsage(String traceId, String tenantId, String modelName,
                                  String system, String user, String result, long durationMs,
                                  ChatResponse response) {
        try {
            // 从 ChatResponse 获取真实 usage
            int promptTokens = 0;
            int completionTokens = 0;
            int totalTokens = 0;

            if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
                var usage = response.getMetadata().getUsage();
                if (usage.getInputTokens() != null) {
                    promptTokens = usage.getInputTokens();
                }
                if (usage.getOutputTokens() != null) {
                    completionTokens = usage.getOutputTokens();
                }
                totalTokens = promptTokens + completionTokens;
            } else {
                // 回退到估算（某些模型可能不支持 usage）
                log.warn("ChatResponse 不包含 usage 信息，回退到估算模式");
                String prompt = (system != null ? system : "") + (user != null ? user : "");
                promptTokens = estimateTokens(prompt);
                completionTokens = estimateTokens(result);
                totalTokens = promptTokens + completionTokens;
            }

            // 生成 traceId（如果没有）
            if (traceId == null || traceId.isBlank()) {
                traceId = UUID.randomUUID().toString();
            }

            // 租户 ID（默认 "default"）
            if (tenantId == null || tenantId.isBlank()) {
                tenantId = "default";
            }

            // 记录 Token 用量
            tokenMeter.record(traceId, tenantId, null, modelName,
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