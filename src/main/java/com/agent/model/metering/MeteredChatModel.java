package com.agent.model.metering;

import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicReference;

/**
 * L6 模型层：Token 计量拦截器（装饰 ChatModel）。
 * <p>包装自动配置的 ChatModel bean（@Primary），对每次 call/stream 记录 Token 用量：
 * 同步在返回后记录；流式在 Flux 完成时记录（provider 通常在最后一块携带 usage，
 * 此处取最后出现的非空 usage）。结构化 entity() 路径底层同样经过 call()，天然全覆盖。</p>
 */
public class MeteredChatModel implements ChatModel {

    private final ChatModel delegate;
    private final TokenMeteringService metering;

    public MeteredChatModel(ChatModel delegate, TokenMeteringService metering) {
        this.delegate = delegate;
        this.metering = metering;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        long start = System.currentTimeMillis();
        ChatResponse resp = delegate.call(prompt);
        Usage usage = resp.getMetadata() == null ? null : resp.getMetadata().getUsage();
        metering.record(prompt, usage, System.currentTimeMillis() - start);
        return resp;
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        long start = System.currentTimeMillis();
        AtomicReference<Usage> lastUsage = new AtomicReference<>();
        return delegate.stream(prompt)
                .doOnNext(r -> {
                    if (r.getMetadata() != null && r.getMetadata().getUsage() != null) {
                        lastUsage.set(r.getMetadata().getUsage());
                    }
                })
                .doOnComplete(() ->
                        metering.record(prompt, lastUsage.get(), System.currentTimeMillis() - start));
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }
}