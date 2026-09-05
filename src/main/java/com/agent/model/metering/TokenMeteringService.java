package com.agent.model.metering;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * L6 模型层：Token 用量计量（CLAUDE.md 铁律"所有对外调用必须记录 Token 用量"）。
 * <p>由 {@code MeteredChatModel} 在每次 LLM 调用（同步/流式/结构化）完成后调用，
 * 记录模型名 / prompt+completion tokens / 时延 / 租户（MDC tenant_id）/ trace_id（MDC）。
 * 结构化 JSON 写 logs/metering.log（METERING logger，同 AUDIT 模式）。
 * <b>P1 用 answerLen 作 Token 代理，P2 起用真实计量。</b></p>
 * <p>W17 成本看板将消费本表数据（届时补 t_token_usage 聚合表，另走设计文档）。</p>
 */
@Service
public class TokenMeteringService {

    private static final Logger METERING = LoggerFactory.getLogger("METERING");
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 记录一次 LLM 调用（同步/流式流结束，为 null 表示 provider 未返回 usage） */
    public void record(Prompt prompt, Usage usage, long latencyMs) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("phase", "llm_call");
        m.put("tenant_id", MDC.get("tenant_id"));
        m.put("trace_id", MDC.get("trace_id"));
        m.put("model", modelName(prompt));
        int promptTokens = usage == null ? -1 : safe(usage.getPromptTokens());
        int completionTokens = usage == null ? -1 : safe(usage.getCompletionTokens());
        m.put("prompt_tokens", promptTokens);
        m.put("completion_tokens", completionTokens);
        m.put("total_tokens", promptTokens < 0 || completionTokens < 0 ? -1 : promptTokens + completionTokens);
        m.put("latency_ms", latencyMs);
        m.put("usage_available", usage != null);
        write(m);
    }

    private int safe(Integer v) {
        return v == null ? -1 : v;
    }

    /** 从 Prompt 的 options 中取模型名（Spring AI 1.0 getOptions() 返回单个 ChatOptions） */
    private String modelName(Prompt prompt) {
        if (prompt == null) {
            return null;
        }
        ChatOptions co = prompt.getOptions();
        return co == null ? null : co.getModel();
    }

    private void write(Map<String, Object> m) {
        try {
            METERING.info(JSON.writeValueAsString(m));
        } catch (Exception e) {
            METERING.warn("metering serialize failed: {}", e.getMessage());
        }
    }
}