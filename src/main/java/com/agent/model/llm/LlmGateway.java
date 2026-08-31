package com.agent.model.llm;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * LLM 网关 v1（L6 模型服务层）
 * <p>
 * 当前：直接代理 Spring AI ChatModel（OpenAI 兼容协议 → DeepSeek）。
 * 后续演进（P1 后期）：模型路由、超时重试、Token 用量计量拦截器、降级链。
 * </p>
 * <p>
 * 架构铁律：应用层禁止直连模型，统一经由此网关（或编排层调用）。
 * </p>
 */
@Service
public class LlmGateway {

    private final ChatModel chatModel;

    public LlmGateway(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /** 单轮生成 */
    public String generate(String system, String user) {
        ChatClient client = ChatClient.builder(chatModel).build();
        return client.prompt()
                .system(system)
                .user(user)
                .call()
                .content();
    }

    /** 流式生成 */
    public Flux<String> stream(String system, String user) {
        ChatClient client = ChatClient.builder(chatModel).build();
        return client.prompt()
                .system(system)
                .user(user)
                .stream()
                .content();
    }

    /** 结构化输出（JSON 模式强约束）：LLM 返回指定类型，用于 Agent 决策等需机器可读的场景
     *  <p>v1 不接 Spring AI 原生 tool_calls（内部自动执行循环与编排层手动执行冲突），
     *  工具描述走 system prompt，返回 JSON 由编排层手动解析执行；W5 工具注册中心落地后再评估切换。</p>
     */
    public <T> T generateStructured(String system, String user, Class<T> outputType) {
        ChatClient client = ChatClient.builder(chatModel).build();
        return client.prompt()
                .system(system)
                .user(user)
                .call()
                .entity(outputType);
    }
}