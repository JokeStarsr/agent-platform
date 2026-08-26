package com.agent.app.chat;

import com.agent.common.Result;
import jakarta.validation.constraints.NotBlank;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

/**
 * 演示接口（对应排期 W1 周末检查点：调用 LLM → 流式返回）
 * 属于 L2 应用层示例，仅验证链路，后续由智能体编排层接管
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatClient chatClient;

    public ChatController(ChatModel chatModel) {
        this.chatClient = ChatClient.builder(chatModel).build();
    }

    /** 单轮问答（非流式） */
    @PostMapping("/ask")
    public Result<String> ask(@RequestBody AskRequest req) {
        String answer = chatClient.prompt().user(req.message()).call().content();
        return Result.ok(answer);
    }

    /** 流式问答（SSE），用于 Web 端打字机效果 */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(@RequestBody AskRequest req) {
        return chatClient.prompt().user(req.message()).stream().content();
    }

    public record AskRequest(@NotBlank(message = "message 不能为空") String message) {
    }
}