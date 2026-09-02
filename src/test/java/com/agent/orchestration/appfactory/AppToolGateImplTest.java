package com.agent.orchestration.appfactory;

import com.agent.common.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * L3 白名单门控单元测试（docs/design/api/20260902-app-factory.md §2.5 AppToolGate）
 * AC：注册应用且工具不在白名单 → 403；注册应用且工具在白名单 → 放行；未注册应用 → 放行；空 appId → 放行。
 */
class AppToolGateImplTest {

    private static final String TENANT = "t1";
    private AppToolGateImpl gate;

    @BeforeEach
    void setUp() {
        // 手工注册表：直接注入 AppRegistry 不方便，用轻量 AppDefinition 构建器模拟
        AppRegistry registry = new AppRegistry(null) {
            @Override
            public AppDefinition get(String tenantId, String appId) {
                if ("tr_booking".equals(appId)) {
                    return AppDefinition.parse("tr_booking", "商旅助手", """
                            {"tools":[{"name":"policy_query","permission":"READ"},
                                      {"name":"book_order","permission":"WRITE"}]}
                            """).withStatus("ENABLED");
                }
                return null;
            }
        };
        gate = new AppToolGateImpl(registry);
    }

    @Test
    void 注册应用且工具在白名单_放行() {
        assertDoesNotThrow(() -> gate.checkToolAllowed(TENANT, "tr_booking", "policy_query"));
    }

    @Test
    void 注册应用且工具不在白名单_403() {
        BizException e = assertThrows(BizException.class,
                () -> gate.checkToolAllowed(TENANT, "tr_booking", "compare_flight"));
        assertEquals(403, e.getCode());
        assertEquals("工具 'compare_flight' 不在应用 'tr_booking' 的白名单中", e.getMessage());
    }

    @Test
    void 未注册应用_放行() {
        assertDoesNotThrow(() -> gate.checkToolAllowed(TENANT, "unknown_app", "anything"));
    }

    @Test
    void 空appId_放行() {
        assertDoesNotThrow(() -> gate.checkToolAllowed(TENANT, "", "anything"));
        assertDoesNotThrow(() -> gate.checkToolAllowed(TENANT, null, "anything"));
    }
}