package com.agent.model.metering;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * L6 模型层：Token 计量装配（MeteredChatModel @Primary 包裹自动配置的 ChatModel）。
 * <p>@Primary 保证所有注入 ChatModel 的地方（LlmGateway 及潜在直连方）拿到的是计量装饰器；
 * 原始 bean 仍由 Spring AI 自动配置管理，装饰器全量委托且仅附加计量副作用。</p>
 */
@Configuration
public class LlmMeterConfig {

    @Bean
    @Primary
    public ChatModel meteredChatModel(ChatModel chatModel, TokenMeteringService metering) {
        return new MeteredChatModel(chatModel, metering);
    }
}