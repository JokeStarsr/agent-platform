package com.agent.orchestration.agent;

import com.agent.data.agentrun.AgentRunRepository;
import com.agent.model.llm.LlmGateway;
import com.agent.orchestration.appfactory.AppRegistry;
import com.agent.tool.AgentTool;
import com.agent.tool.ToolEngineService;
import com.agent.tool.ToolEngineService.InvokeRequest;
import com.agent.tool.ToolEngineService.ToolInvokeResult;
import com.agent.tool.ToolPermission;
import com.agent.tool.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Agent Runtime 核心单测（docs/design/architecture/20260831-agent-runtime.md §7 验收用例 + 冷却分支）
 * W5 起工具经 ToolEngine 执行（真实注册中心 + mock 引擎），不依赖真实 LLM/PostgreSQL。
 */
class AgentRuntimeServiceImplTest {

    private static final String TENANT = "default";
    private static final String APP = "CS_AGENT";

    private AgentRunRepository repo;
    private LlmGateway llm;
    private ToolEngineService toolEngine;
    private AgentRuntimeServiceImpl cut;
    private final AtomicInteger callIdx = new AtomicInteger();

    @BeforeEach
    void setUp() {
        repo = mock(AgentRunRepository.class);
        llm = mock(LlmGateway.class);
        toolEngine = mock(ToolEngineService.class);
        when(repo.countRunning(anyString())).thenReturn(0);
        when(repo.createRun(anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyInt(), anyInt(), anyInt(), anyString())).thenReturn(1L, 2L);
        when(repo.findPending(1L)).thenReturn(Optional.empty());
        when(repo.runDetail(1L)).thenReturn(detailMap("RUNNING"));
        // 工具执行默认返回：读工具→订单数据；写工具→发券结果（按工具名分发）
        when(toolEngine.invoke(eq(TENANT), anyString(), any(InvokeRequest.class)))
                .thenAnswer(inv -> {
                    InvokeRequest req = inv.getArgument(2);
                    if ("query_recent_orders".equals(req.tool())) {
                        return ToolInvokeResult.ok(Map.of("orders", List.of("SO001")));
                    }
                    return ToolInvokeResult.ok(Map.of("result", "ok", "coupon", "已发放"));
                });
        ToolRegistry registry = new ToolRegistry(List.of(new OrderQueryToolStub(), new SendCouponToolStub()));
        cut = new AgentRuntimeServiceImpl(repo, llm, toolEngine, registry, mock(AppRegistry.class));
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
        cut.submit(TENANT, APP, "查一下我的最近订单", null);

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
                new AgentStepIntent("补偿用户，需要发券", "TOOL_CALL", "send_coupon", Map.of("userId", "u1"), null),
                new AgentStepIntent("发券被拒，直接回答", "FINAL_ANSWER", null, null, "已确认不发放补偿券"));
        cut.submit(TENANT, APP, "用户要补偿券", null);

        verify(repo, timeout(5000)).hangForApproval(eq(1L), eq("send_coupon"), anyMap());
        cut.approve(TENANT, 1L, false); // 拒绝写操作

        verify(repo, timeout(5000)).updateStatus(eq(1L), eq(AgentRunStatus.COMPLETED.name()),
                isNull(), anyInt(), anyInt());
        verify(toolEngine, never()).invoke(eq(TENANT), anyString(),
                argThat((InvokeRequest r) -> "send_coupon".equals(r.tool())));
    }

    @Test
    void ac2b_写操作审批通过_经引擎幂等执行() throws Exception {
        when(repo.findPending(1L)).thenReturn(Optional.of(
                new AgentRunRepository.PendingApproval(TENANT, "send_coupon", Map.of())));
        withIntents(
                new AgentStepIntent("补偿用户，发券", "TOOL_CALL", "send_coupon", Map.of("userId", "u1"), null),
                new AgentStepIntent("发券完成，作答", "FINAL_ANSWER", null, null, "补偿券已发放"));
        cut.submit(TENANT, APP, "用户要补偿券", null);

        verify(repo, timeout(5000)).hangForApproval(eq(1L), eq("send_coupon"), anyMap());
        cut.approve(TENANT, 1L, true);

        verify(repo, timeout(5000)).updateStatus(eq(1L), eq(AgentRunStatus.COMPLETED.name()),
                isNull(), anyInt(), anyInt());
        // 写操作必须携带幂等键（决策级）
        verify(toolEngine, timeout(5000)).invoke(eq(TENANT), eq(APP),
                argThat((InvokeRequest r) -> "send_coupon".equals(r.tool()) && r.idempotencyKey() != null));
    }

    @Test
    void ac2c_写操作执行失败_重试换新幂等键() throws Exception {
        when(repo.findPending(1L)).thenReturn(Optional.of(
                new AgentRunRepository.PendingApproval(TENANT, "send_coupon", Map.of())));
        // 引擎首次调用超时（504）→ Runtime 换新键重试一次成功
        when(toolEngine.invoke(eq(TENANT), eq(APP), any(InvokeRequest.class)))
                .thenThrow(new com.agent.common.BizException(504, "工具执行超时"))
                .thenAnswer(inv -> ToolInvokeResult.ok(Map.of("result", "ok", "coupon", "已发放")));
        withIntents(
                new AgentStepIntent("发券", "TOOL_CALL", "send_coupon", Map.of("userId", "u1"), null),
                new AgentStepIntent("发完了", "FINAL_ANSWER", null, null, "done"));
        cut.submit(TENANT, APP, "用户要补偿券", null);

        verify(repo, timeout(5000)).hangForApproval(eq(1L), eq("send_coupon"), anyMap());
        cut.approve(TENANT, 1L, true);

        verify(repo, timeout(5000)).updateStatus(eq(1L), eq(AgentRunStatus.COMPLETED.name()),
                isNull(), anyInt(), anyInt());
        var cap = ArgumentCaptor.forClass(InvokeRequest.class);
        verify(toolEngine, times(2)).invoke(eq(TENANT), eq(APP), cap.capture());
        List<String> keys = cap.getAllValues().stream().map(InvokeRequest::idempotencyKey).distinct().toList();
        assertEquals(2, keys.size(), "超时重试必须换新幂等键（旧键已 FAILED 不可复用）");
    }

    @Test
    void ac3_预算耗尽_优雅终止() throws Exception {
        // 每步固定 500 token，budget=1000 → 第 2 次 check 时触发
        AgentConfig budgetConfig = new AgentConfig(10, 1000, 60_000, 3, 5);
        withIntents(new AgentStepIntent("继续思考", "REASON", null, null, null));
        cut.submit(TENANT, APP, "无限思考任务", budgetConfig);

        verify(repo, timeout(5000)).updateStatus(eq(1L), eq(AgentRunStatus.BUDGET_EXHAUSTED.name()),
                argThat(s -> s != null && s.contains("Token 预算")), anyInt(), anyInt());
    }

    @Test
    void ac4_超时_任务标记TIMEOUT_可重放() throws Exception {
        AgentConfig shortConfig = new AgentConfig(10, 32_000, 50, 3, 5);
        withIntents(80, new AgentStepIntent("思考中", "REASON", null, null, null));
        cut.submit(TENANT, APP, "超时任务", shortConfig);

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
        cut.submit(TENANT, APP, "循环任务", null);

        verify(repo, timeout(5000)).updateStatus(eq(1L), eq(AgentRunStatus.TERMINATED.name()),
                argThat(s -> s != null && s.contains("循环重复")), anyInt(), anyInt());
    }

    @Test
    void 取消运行_状态置CANCELED() throws Exception {
        withIntents(50, new AgentStepIntent("思考中", "REASON", null, null, null));
        cut.submit(TENANT, APP, "取消任务", null);

        verify(repo, timeout(3000).atLeastOnce()).appendStep(anyLong(), any(AgentRunRepository.StepRow.class));
        cut.cancel(TENANT, 1L);
        verify(repo, atLeastOnce()).updateStatus(eq(1L), eq(AgentRunStatus.CANCELED.name()), anyString(), anyInt(), anyInt());
    }

    @Test
    void retry_TIMEOUT后可重试_新建运行() throws Exception {
        when(repo.runDetail(1L)).thenReturn(detailMap("TIMEOUT"));
        AgentConfig shortConfig = new AgentConfig(10, 32_000, 50, 3, 5);
        withIntents(80, new AgentStepIntent("思考中", "REASON", null, null, null));
        cut.submit(TENANT, APP, "超时任务", shortConfig);
        verify(repo, timeout(5000)).updateStatus(eq(1L), eq(AgentRunStatus.TIMEOUT.name()), anyString(),
                anyInt(), anyInt());

        long newRun = cut.retry(TENANT, 1L);
        assertNotEquals(1L, newRun);
    }

    @Test
    void submit_任务为空_拒绝() {
        assertThrows(com.agent.common.BizException.class,
                () -> cut.submit(TENANT, APP, "  ", null));
    }

    @Test
    void submit_并发超限_拒绝() {
        when(repo.countRunning(TENANT)).thenReturn(5);
        assertThrows(com.agent.common.BizException.class,
                () -> cut.submit(TENANT, APP, "任务", null));
    }

    /* ---------- 工具 Stub（与生产工具同语义，注册进真实 ToolRegistry） ---------- */

    static class OrderQueryToolStub implements AgentTool {
        @Override public String name() { return "query_recent_orders"; }
        @Override public String description() { return "查询最近订单"; }
        @Override public ToolPermission permission() { return ToolPermission.READ; }
        @Override public Map<String, Object> execute(Map<String, Object> args) {
            return Map.of("orders", List.of("SO001"));
        }
    }

    static class SendCouponToolStub implements AgentTool {
        @Override public String name() { return "send_coupon"; }
        @Override public String description() { return "发补偿券"; }
        @Override public ToolPermission permission() { return ToolPermission.WRITE; }
        @Override public Map<String, Object> execute(Map<String, Object> args) {
            return Map.of("result", "ok");
        }
    }

    /* ---------- 帮助 ---------- */

    private void withIntents(AgentStepIntent... intents) {
        withIntents(0, intents);
    }

    private void withIntents(long llmSleepMs, AgentStepIntent... intents) {
        callIdx.set(0);
        when(llm.generateStructured(anyString(), anyString(), eq(AgentStepIntent.class))).thenAnswer(inv -> {
            if (llmSleepMs > 0) {
                Thread.sleep(llmSleepMs);
            }
            int i = Math.min(callIdx.getAndIncrement(), intents.length - 1);
            return intents[i];
        });
    }

    private Map<String, Object> detailMap(String status) {
        Map<String, Object> d = new java.util.LinkedHashMap<>();
        d.put("runId", 1L);
        d.put("tenantId", TENANT);
        d.put("appId", APP);
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