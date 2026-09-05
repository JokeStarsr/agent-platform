package com.agent.model.metering;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MeteredChatModelTest {

    private ChatModel delegate;
    private TokenMeteringService metering;
    private MeteredChatModel model;

    /** 记录 record() 被调用时的 usage 参数 */
    private final AtomicReference<Usage> recordedUsage = new AtomicReference<>();
    private int recordCalls;

    @BeforeEach
    void setUp() {
        delegate = mock(ChatModel.class);
        metering = mock(TokenMeteringService.class);
        model = new MeteredChatModel(delegate, metering);
        recordCalls = 0;
        recordedUsage.set(null);
        doAnswer(inv -> {
            recordCalls++;
            recordedUsage.set(inv.getArgument(1));
            return null;
        }).when(metering).record(any(), any(), anyLong());
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    private static ChatResponse makeResp(Usage usage) {
        Generation gen = new Generation(new AssistantMessage("hi"));
        ChatResponseMetadata.Builder metaBuilder = ChatResponseMetadata.builder();
        if (usage != null) {
            metaBuilder.usage(usage);
        }
        return ChatResponse.builder()
                .generations(List.of(gen))
                .metadata(metaBuilder.build())
                .build();
    }

    @Test
    void call_delegatesAndRecordsUsage() {
        Usage usage = new DefaultUsage(10, 20, 30);
        when(delegate.call(any(Prompt.class))).thenReturn(makeResp(usage));

        ChatResponse resp = model.call(new Prompt("hi"));

        assertNotNull(resp);
        verify(metering, times(1)).record(any(), any(), anyLong());
        assertSame(usage, recordedUsage.get());
    }

    @Test
    void call_noUsage_recordsEmptyUsage() {
        // ChatResponseMetadata 未显式 setUsage 时，getUsage() 返回 EmptyUsage（非 null）
        when(delegate.call(any(Prompt.class))).thenReturn(makeResp(null));

        model.call(new Prompt("hi"));

        verify(metering, times(1)).record(any(), any(), anyLong());
        assertNotNull(recordedUsage.get());
        // EmptyUsage 的 token 可能为 null 或 0，service.safe() 都能处理
    }

    @Test
    void call_exception_notRecorded() {
        when(delegate.call(any(Prompt.class))).thenThrow(new RuntimeException("upstream down"));

        try {
            model.call(new Prompt("hi"));
        } catch (RuntimeException ignored) {
        }
        verify(metering, never()).record(any(), any(), anyLong());
    }

    @Test
    void stream_recordsUsageOnComplete() {
        Usage finalUsage = new DefaultUsage(5, 8, 13);
        when(delegate.stream(any(Prompt.class))).thenReturn(Flux.just(
                makeResp(null),
                makeResp(finalUsage)));

        model.stream(new Prompt("hi")).blockLast();

        verify(metering, times(1)).record(any(), any(), anyLong());
        assertSame(finalUsage, recordedUsage.get());
    }

    @Test
    void stream_usageOnMiddleChunk_keepsLastValue() {
        Usage usage1 = new DefaultUsage(1, 1, 2);
        Usage usage2 = new DefaultUsage(50, 60, 110);
        when(delegate.stream(any(Prompt.class))).thenReturn(Flux.just(
                makeResp(usage1),
                makeResp(usage2)));

        model.stream(new Prompt("hi")).blockLast();

        verify(metering, times(1)).record(any(), any(), anyLong());
        assertSame(usage2, recordedUsage.get());
    }

    @Test
    void modelName_fromPromptOptions() {
        TokenMeteringService svc = new TokenMeteringService();
        ChatOptions opts = mock(ChatOptions.class);
        when(opts.getModel()).thenReturn("zen-model");
        Prompt p = new Prompt("hi", opts);
        MDC.put("tenant_id", "t1");
        MDC.put("trace_id", "tid-1");
        // 只验证不抛异常且 JSON 序列化路径正常
        svc.record(p, new DefaultUsage(1, 2, 3), 10);
    }
}