package com.agent.app.chat;

import com.agent.common.Result;
import com.agent.model.llm.LlmGateway;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

/**
 * 演示接口（对应排期 W1 周末检查点：调用 LLM → 流式返回）
 * 属于 L2 应用层示例，仅验证链路，后续由智能体编排层接管。
 * 模型调用一律走 L6 LlmGateway（架构铁律：应用层禁止直连模型）。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final LlmGateway llmGateway;

    public ChatController(LlmGateway llmGateway) {
        this.llmGateway = llmGateway;
    }

    /** 单轮问答（非流式）；model 字段可选，覆盖默认模型（用于双模型互判等场景） */
    @PostMapping("/ask")
    public Result<String> ask(@RequestBody AskRequest req) {
        if (req.model() != null && !req.model().isBlank()) {
            return Result.ok(llmGateway.generateWithModel("", req.message(), req.model()));
        }
        return Result.ok(llmGateway.generate("", req.message()));
    }

    /** 流式问答（SSE），用于 Web 端打字机效果 */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(@RequestBody AskRequest req) {
        return llmGateway.stream("", req.message());
    }

    public record AskRequest(@NotBlank(message = "message 不能为空") String message, String model) {
    }
}