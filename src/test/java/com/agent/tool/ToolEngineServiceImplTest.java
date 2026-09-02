package com.agent.tool;

import com.agent.common.BizException;
import com.agent.data.toolinvocation.ToolInvocationRepository;
import com.agent.data.toolinvocation.ToolInvocationRepository.InvocationRow;
import com.agent.tool.ToolEngineService.InvokeRequest;
import com.agent.tool.ToolEngineService.ToolInvokeResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 工具引擎核心单测（docs/design/architecture/20260901-tool-engine.md §5 验收用例 TC-1..6）
 * Mock 幂等仓库 + 真实注册中心/引擎，不依赖 PostgreSQL。
 */
class ToolEngineServiceImplTest {

    private static final String TENANT = "default";
    private static final String APP = "CS_AGENT";

    private ToolInvocationRepository repo;
    private ToolEngineServiceImpl engine;
    private AtomicReference<InvocationRow> invocationStore;
    private final AtomicInteger sendCouponCalls = new AtomicInteger();

    @BeforeEach
    void setUp() {
        repo = mock(ToolInvocationRepository.class);
        invocationStore = new AtomicReference<>();
        // 幂等仓库内存行为：findByKey 读 store；tryCreate 占用；markFinished 写结果
        when(repo.findByKey(anyString())).thenAnswer(inv -> Optional.ofNullable(invocationStore.get()));
        when(repo.tryCreateInProgress(anyString(), anyString(), anyString(), anyString())).thenAnswer((Answer<Boolean>) inv -> {
            if (invocationStore.get() != null) {
                return false;
            }
            invocationStore.set(new InvocationRow(1L, inv.getArgument(0), inv.getArgument(1),
                    inv.getArgument(2), inv.getArgument(3), null, "IN_PROGRESS", Instant.now(), null));
            return true;
        });
        doAnswer(inv -> {
            InvocationRow cur = invocationStore.get();
            if (cur != null) {
                invocationStore.set(new InvocationRow(cur.invocationId(), cur.idempotencyKey(), cur.tenantId(),
                        cur.toolName(), cur.argsHash(), inv.getArgument(2), inv.getArgument(1),
                        cur.createdAt(), Instant.now()));
            }
            return null;
        }).when(repo).markFinished(anyString(), anyString(), anyString());

        ToolRegistry registry = new ToolRegistry(List.of(
                new QueryToolStub(), new CouponToolStub(), new RefundToolStub(), new PaymentToolStub(),
                new SlowWriteToolStub()));
        engine = new ToolEngineServiceImpl(registry, repo, null);
    }

    /* ---------- TC-1 幂等重放 ---------- */

    @Test
    void tc1_同幂等键重复调用_第二次重放首次结果不重复执行() {
        InvokeRequest first = new InvokeRequest("send_coupon", Map.of("userId", "u1"), "K-001");
        InvokeRequest second = new InvokeRequest("send_coupon", Map.of("userId", "u1"), "K-001");

        ToolInvokeResult r1 = engine.invoke(TENANT, APP, first);
        ToolInvokeResult r2 = engine.invoke(TENANT, APP, second);

        assertFalse(r1.idempotentReplay(), "首次应真实执行");
        assertEquals("ok", r1.data().get("result"));
        assertTrue(r2.idempotentReplay(), "重复键应幂等重放");
        assertEquals(1, sendCouponCalls.get(), "工具只能真实执行 1 次");
    }

    /* ---------- TC-2 缺幂等键拒绝 ---------- */

    @Test
    void tc2_写操作不带幂等键_400拒绝且工具未执行() {
        InvokeRequest req = new InvokeRequest("send_coupon", Map.of("userId", "u1"), null);
        BizException ex = assertThrows(BizException.class, () -> engine.invoke(TENANT, APP, req));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("幂等键"));
        assertEquals(0, sendCouponCalls.get(), "工具不应执行");
    }

    /* ---------- TC-3 参数 Schema 校验 ---------- */

    @Test
    void tc3_参数校验_负数金额400() {
        InvokeRequest req = new InvokeRequest("refund_order_partial",
                Map.of("orderId", "SO001", "refundAmount", -5), "K-002");
        BizException ex = assertThrows(BizException.class, () -> engine.invoke(TENANT, APP, req));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("参数校验失败"));
    }

    @Test
    void tc3b_参数校验_超上限400() {
        InvokeRequest req = new InvokeRequest("refund_order_partial",
                Map.of("orderId", "SO001", "refundAmount", 600), "K-003");
        BizException ex = assertThrows(BizException.class, () -> engine.invoke(TENANT, APP, req));
        assertEquals(400, ex.getCode());
    }

    @Test
    void tc3c_参数校验_合法参数执行成功() {
        InvokeRequest req = new InvokeRequest("refund_order_partial",
                Map.of("orderId", "SO001", "refundAmount", 100), "K-004");
        ToolInvokeResult r = engine.invoke(TENANT, APP, req);
        assertEquals(100.0, ((Number) r.data().get("refunded")).doubleValue());
    }

    /* ---------- TC-4 执行超时 ---------- */

    @Test
    void tc4_工具执行超时_504可重试() {
        InvokeRequest req = new InvokeRequest("slow_write", Map.of("x", 1), "K-005");
        BizException ex = assertThrows(BizException.class, () -> engine.invoke(TENANT, APP, req));
        assertEquals(504, ex.getCode());
        assertTrue(ex.getMessage().contains("超时"));
        // 超时后幂等记录应标记 FAILED（换键可重试）
        assertEquals("FAILED", invocationStore.get().status());
    }

    /* ---------- TC-5 未注册工具 ---------- */

    @Test
    void tc5_未注册工具_404() {
        InvokeRequest req = new InvokeRequest("not_exists", Map.of(), null);
        BizException ex = assertThrows(BizException.class, () -> engine.invoke(TENANT, APP, req));
        assertEquals(404, ex.getCode());
    }

    /* ---------- TC-6 PAYMENT 拒绝 ---------- */

    @Test
    void tc6_支付级工具_403拒绝不放开() {
        InvokeRequest req = new InvokeRequest("pay_order", Map.of(), "K-006");
        BizException ex = assertThrows(BizException.class, () -> engine.invoke(TENANT, APP, req));
        assertEquals(403, ex.getCode());
        assertTrue(ex.getMessage().contains("支付级"));
    }

    /* ---------- Stubs ---------- */

    static class QueryToolStub implements AgentTool {
        @Override public String name() { return "query_recent_orders"; }
        @Override public String description() { return "查订单"; }
        @Override public ToolPermission permission() { return ToolPermission.READ; }
        @Override public Map<String, Object> execute(Map<String, Object> args) {
            return Map.of("orders", List.of("SO001"));
        }
    }

    class CouponToolStub implements AgentTool {
        @Override public String name() { return "send_coupon"; }
        @Override public String description() { return "发券"; }
        @Override public ToolPermission permission() { return ToolPermission.WRITE; }
        @Override public Map<String, Object> execute(Map<String, Object> args) {
            sendCouponCalls.incrementAndGet();
            return Map.of("result", "ok");
        }
    }

    static class RefundToolStub implements AgentTool {
        @Override public String name() { return "refund_order_partial"; }
        @Override public String description() { return "部分退款"; }
        @Override public String parameters() {
            return "{\"type\":\"object\",\"properties\":{"
                    + "\"orderId\":{\"type\":\"string\"},"
                    + "\"refundAmount\":{\"type\":\"number\",\"minimum\":0,\"maximum\":500}"
                    + "},\"required\":[\"orderId\",\"refundAmount\"]}";
        }
        @Override public ToolPermission permission() { return ToolPermission.WRITE; }
        @Override public Map<String, Object> execute(Map<String, Object> args) {
            return Map.of("result", "ok", "refunded", (Number) args.get("refundAmount"));
        }
    }

    static class PaymentToolStub implements AgentTool {
        @Override public String name() { return "pay_order"; }
        @Override public String description() { return "支付（未开放）"; }
        @Override public ToolPermission permission() { return ToolPermission.PAYMENT; }
        @Override public Map<String, Object> execute(Map<String, Object> args) {
            return Map.of("paid", true);
        }
    }

    /** 超时写工具：timeout 50ms，执行睡 2s */
    static class SlowWriteToolStub implements AgentTool {
        @Override public String name() { return "slow_write"; }
        @Override public String description() { return "慢写"; }
        @Override public ToolPermission permission() { return ToolPermission.WRITE; }
        @Override public long timeoutMs() { return 50; }
        @Override public Map<String, Object> execute(Map<String, Object> args) {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return Map.of("done", true);
        }
    }
}