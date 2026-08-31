package com.agent.tool.tools;

import com.agent.common.BizException;
import com.agent.data.toolinvocation.ToolInvocationRepository;
import com.agent.tool.ToolEngineService.InvokeRequest;
import com.agent.tool.ToolEngineServiceImpl;
import com.agent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 支付门控硬测试（docs/design/architecture/20260901-w8-trip-scenario.md §7 AC-2 支付无确认不可达）
 * pay_order 以 PAYMENT 权限注册 → 引擎硬 403，任何路径不可达（工具层保证，非编排约定）。
 */
class PaymentGateTest {

    @Test
    void 支付工具_任何路径调用均403() {
        ToolRegistry registry = new ToolRegistry(List.of(new PayOrderTool()));
        ToolEngineServiceImpl engine = new ToolEngineServiceImpl(registry, mock(ToolInvocationRepository.class));

        BizException e = assertThrows(BizException.class,
                () -> engine.invoke("default", "TR_DEMO", new InvokeRequest("pay_order", Map.of("orderId", "O1"), null)));
        assertTrue(e.getMessage().contains("支付级工具未开放"), e.getMessage());
        assertThrows(BizException.class,
                () -> engine.invoke("default", "TR_DEMO",
                        new InvokeRequest("pay_order", Map.of("orderId", "O1"), "some-key")));
    }
}