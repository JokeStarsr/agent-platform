package com.agent.model.llm;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * LLM 网关（L6 模型服务层）
 * <p>统一经本网关访问模型（架构铁律：应用层禁止直连模型）。
 * system 为空时跳过 .system()（Spring AI 1.0 的 Assert.hasText 拒绝空 system 字符串）。
 * ChatClient builder 不可变，链式调用每个方法都返回新 spec。</p>
 */
@Service
public class LlmGateway {

    private final ChatModel chatModel;

    public LlmGateway(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /** 单轮生成 */
    public String generate(String system, String user) {
        ChatClient.ChatClientRequestSpec spec = applySystem(spec().user(user), system);
        return spec.call().content();
    }

    /** 单轮生成（指定模型名覆盖默认配置，用于 LLM-as-judge 双模型互判等场景） */
    public String generateWithModel(String system, String user, String model) {
        ChatClient.ChatClientRequestSpec spec = applySystem(spec().user(user), system);
        if (model != null && !model.isBlank()) {
            spec = spec.options(OpenAiChatOptions.builder().model(model).build());
        }
        return spec.call().content();
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
}