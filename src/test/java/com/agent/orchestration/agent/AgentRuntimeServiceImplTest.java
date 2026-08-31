package com.agent.orchestration.agent;

import com.agent.data.agentrun.AgentRunRepository;
import com.agent.model.llm.LlmGateway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Answer;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Agent Runtime 核心单测（docs/design/architecture/20260831-agent-runtime.md §7 验收用例 + 冷却分支）
 * Mock LlmGateway 与 Repository，不依赖真实 LLM/PostgreSQL。
 */
class AgentRuntimeServiceImplTest {

    private static final String TENANT = "default";

    private AgentRunRepository repo;
    private LlmGateway llm;
    private AgentRuntimeServiceImpl cut;
    private final AtomicInteger callIdx = new AtomicInteger();

    @BeforeEach
    void setUp() {
        repo = mock(AgentRunRepository.class);
        llm = mock(LlmGateway.class);
        when(repo.countRunning(anyString())).thenReturn(0);
        when(repo.createRun(anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyInt(), anyInt(), anyInt(), anyString())).thenReturn(1L, 2L);
        when(repo.findPending(1L)).thenReturn(Optional.empty());
        when(repo.runDetail(1L)).thenReturn(detailMap("RUNNING"));
        cut = new AgentRuntimeServiceImpl(repo, llm,
                List.of(new OrderQueryToolStub(), new SendCouponToolStub()));
    }

    @AfterEach
    void tearDown() {
        // 取消可能因运行已结束而抛 409（非 RUNNING），忽略——目的是结束残留执行线程
        try {
            cut.cancel(TENANT, 1L);
        } catch (Exception ignored) {
        }
    }

    /* ---------- 验收用例 ---------- */

    @Test
    void ac1_正常完成_工具调用后最终回答() throws Exception {
        withIntents(
                new AgentStepIntent("查最近订单", "TOOL_CALL", "query_recent_orders", Map.of("userId", "u1"), null),
                new AgentStepIntent("订单已查到，作答", "FINAL_ANSWER", null, null, "你最近有3笔订单"));
        cut.submit(TENANT, "CS_AGENT", "查一下我的最近订单", null);

        verify(repo, timeout(5000)).updateStatus(eq(1L), eq(AgentRunStatus.COMPLETED.name()),
                isNull(), anyInt(), anyInt());
        var cap = ArgumentCaptor.forClass(AgentRunRepository.StepRow.class);
        verify(repo, atLeastOnce()).appendStep(anyLong(), cap.capture());
        assertTrue(cap.getAllValues().stream().anyMatch(ev -> "TOOL_CALL".equals(ev.phase())
                && "query_recent_orders".equals(ev.tool())), "应发出 query_recent_orders 的 TOOL_CALL trace");
    }

    @Test
    void ac2_写操作未审批_挂起等待审批_拒绝后跳过继续() throws Exception {
        when(repo.findPending(1L)).thenReturn(Optional.of(
                new AgentRunRepository.PendingApproval(TENANT, "send_coupon", Map.of())));
        withIntents(
                new AgentStepIntent("补偿用户，需要发券", "TOOL_CALL", "send_coupon", Map.of("userId", "u1", "couponId", "C1"), null),
                new AgentStepIntent("发券被拒，直接回答", "FINAL_ANSWER", null, null, "已确认不发放补偿券"));
        cut.submit(TENANT, "CS_AGENT", "用户要补偿券", null);

        verify(repo, timeout(5000)).hangForApproval(eq(1L), eq("send_coupon"), anyMap());
        cut.approve(TENANT, 1L, false); // 拒绝写操作

        verify(repo, timeout(5000)).updateStatus(eq(1L), eq(AgentRunStatus.COMPLETED.name()),
                isNull(), anyInt(), anyInt());
        var cap = ArgumentCaptor.forClass(AgentRunRepository.StepRow.class);
        verify(repo, atLeastOnce()).appendStep(anyLong(), cap.capture());
        assertTrue(cap.getAllValues().stream().noneMatch(ev -> "TOOL_RESULT".equals(ev.phase())
                && "send_coupon".equals(ev.tool())), "被拒的写操作不应产生 TOOL_RESULT trace");
    }

    @Test
    void ac2b_写操作审批通过_执行写工具() throws Exception {
        when(repo.findPending(1L)).thenReturn(Optional.of(
                new AgentRunRepository.PendingApproval(TENANT, "send_coupon", Map.of())));
        withIntents(
                new AgentStepIntent("补偿用户，发券", "TOOL_CALL", "send_coupon", Map.of("userId", "u1", "couponId", "C1"), null),
                new AgentStepIntent("发券完成，作答", "FINAL_ANSWER", null, null, "补偿券已发放"));
        cut.submit(TENANT, "CS_AGENT", "用户要补偿券", null);

        verify(repo, timeout(5000)).hangForApproval(eq(1L), eq("send_coupon"), anyMap());
        cut.approve(TENANT, 1L, true);

        verify(repo, timeout(5000)).updateStatus(eq(1L), eq(AgentRunStatus.COMPLETED.name()),
                isNull(), anyInt(), anyInt());
        var cap = ArgumentCaptor.forClass(AgentRunRepository.StepRow.class);
        verify(repo, atLeastOnce()).appendStep(anyLong(), cap.capture());
        assertTrue(cap.getAllValues().stream().anyMatch(ev -> "TOOL_RESULT".equals(ev.phase())
                && "send_coupon".equals(ev.tool())), "审批通过的写操作应产生 TOOL_RESULT trace");
    }

    @Test
    void ac3_预算耗尽_优雅终止() throws Exception {
        // 每步固定 500 token，budget=1000 → 第 2 次 check 时触发
        AgentConfig budgetConfig = new AgentConfig(10, 1000, 60_000, 3, 5);
        withIntents(new AgentStepIntent("继续思考", "REASON", null, null, null));
        cut.submit(TENANT, "CS_AGENT", "无限思考任务", budgetConfig);

        verify(repo, timeout(5000)).updateStatus(eq(1L), eq(AgentRunStatus.BUDGET_EXHAUSTED.name()),
                argThat(s -> s != null && s.contains("Token 预算")), anyInt(), anyInt());
        ArgumentCaptor<Integer> tokensCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(repo).updateStatus(eq(1L), eq(AgentRunStatus.BUDGET_EXHAUSTED.name()), anyString(), anyInt(), tokensCaptor.capture());
        assertTrue(tokensCaptor.getValue() >= 1000, "触发时累计 token 应 ≥ 预算");
    }

    @Test
    void ac4_超时_任务标记TIMEOUT_可重放() throws Exception {
        AgentConfig shortConfig = new AgentConfig(10, 32_000, 50, 3, 5);
        // 每次 LLM 调用模拟耗时 80ms，第一轮后 elapsed 已超 50ms → 下一轮 check 触发 TIMEOUT
        withIntents(80, new AgentStepIntent("思考中", "REASON", null, null, null));
        cut.submit(TENANT, "CS_AGENT", "超时任务", shortConfig);

        verify(repo, timeout(5000)).updateStatus(eq(1L), eq(AgentRunStatus.TIMEOUT.name()),
                argThat(s -> s != null && s.contains("超时")), anyInt(), anyInt());
    }

    @Test
    void 循环检测_连续相同动作3次后终止() throws Exception {
        withIntents(
                new AgentStepIntent("查订单", "TOOL_CALL", "query_recent_orders", Map.of("userId", "u1"), null),
                new AgentStepIntent("再查一次", "TOOL_CALL", "query_recent_orders", Map.of("userId", "u1"), null),
                new AgentStepIntent("继续查", "TOOL_CALL", "query_recent_orders", Map.of("userId", "u1"), null),
                new AgentStepIntent("又查", "TOOL_CALL", "query_recent_orders", Map.of("userId", "u1"), null));
        cut.submit(TENANT, "CS_AGENT", "循环任务", null);

        verify(repo, timeout(5000)).updateStatus(eq(1L), eq(AgentRunStatus.TERMINATED.name()),
                argThat(s -> s != null && s.contains("循环重复")), anyInt(), anyInt());
    }

    @Test
    void 取消运行_状态置CANCELED() throws Exception {
        withIntents(50, new AgentStepIntent("思考中", "REASON", null, null, null));
        cut.submit(TENANT, "CS_AGENT", "取消任务", null);

        verify(repo, timeout(3000).atLeastOnce()).appendStep(anyLong(), any(AgentRunRepository.StepRow.class));
        cut.cancel(TENANT, 1L);
        verify(repo, atLeastOnce()).updateStatus(eq(1L), eq(AgentRunStatus.CANCELED.name()), anyString(), anyInt(), anyInt());
    }

    @Test
    void retry_TIMEOUT后可重试_新建运行() throws Exception {
        when(repo.runDetail(1L)).thenReturn(detailMap("TIMEOUT"));
        AgentConfig shortConfig = new AgentConfig(10, 32_000, 50, 3, 5);
        withIntents(80, new AgentStepIntent("思考中", "REASON", null, null, null));
        cut.submit(TENANT, "CS_AGENT", "超时任务", shortConfig);
        verify(repo, timeout(5000)).updateStatus(eq(1L), eq(AgentRunStatus.TIMEOUT.name()), anyString(),
                anyInt(), anyInt());

        long newRun = cut.retry(TENANT, 1L);
        assertNotEquals(1L, newRun);
    }

    @Test
    void submit_任务为空_拒绝() {
        assertThrows(com.agent.common.BizException.class,
                () -> cut.submit(TENANT, "CS_AGENT", "  ", null));
    }

    @Test
    void submit_并发超限_拒绝() {
        when(repo.countRunning(TENANT)).thenReturn(5);
        assertThrows(com.agent.common.BizException.class,
                () -> cut.submit(TENANT, "CS_AGENT", "任务", null));
    }

    /* ---------- 工具 Stub（与生产工具同语义，测试内联避免依赖真实组件） ---------- */

    static class OrderQueryToolStub implements AgentTool {
        @Override public String name() { return "query_recent_orders"; }
        @Override public String description() { return "查询最近订单"; }
        @Override public String argsSchema() { return "{}"; }
        @Override public boolean write() { return false; }
        @Override public Map<String, Object> execute(Map<String, Object> args) {
            return Map.of("orders", List.of("SO001"));
        }
    }

    static class SendCouponToolStub implements AgentTool {
        @Override public String name() { return "send_coupon"; }
        @Override public String description() { return "发补偿券"; }
        @Override public String argsSchema() { return "{}"; }
        @Override public boolean write() { return true; }
        @Override public Map<String, Object> execute(Map<String, Object> args) {
            return Map.of("result", "ok");
        }
    }

    /* ---------- 帮助 ---------- */

    /** 按序返回 intents，最后一个重复使用；每轮 LLM 调用可加固定耗时 */
    private void withIntents(AgentStepIntent... intents) {
        withIntents(0, intents);
    }

    private void withIntents(long llmSleepMs, AgentStepIntent... intents) {
        callIdx.set(0);
        Answer<Object> answer = inv -> {
            if (llmSleepMs > 0) {
                Thread.sleep(llmSleepMs);
            }
            int i = Math.min(callIdx.getAndIncrement(), intents.length - 1);
            return intents[i];
        };
        when(llm.generateStructured(anyString(), anyString(), eq(AgentStepIntent.class))).thenAnswer(answer);
    }

    private Map<String, Object> detailMap(String status) {
        Map<String, Object> d = new java.util.LinkedHashMap<>();
        d.put("runId", 1L);
        d.put("tenantId", TENANT);
        d.put("appId", "CS_AGENT");
        d.put("task", "t");
        d.put("status", status);
        d.put("maxSteps", 10);
        d.put("tokenBudget", 32_000);
        d.put("timeoutMs", 60_000);
        d.put("stepsDone", 0);
        d.put("tokensUsed", 0);
        d.put("traceId", "x");
        d.put("pendingTool", null);
        d.put("pendingArgs", null);
        d.put("unfinishedReason", null);
        return d;
    }
}