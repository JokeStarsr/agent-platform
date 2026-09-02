package com.agent.orchestration.appfactory;

import com.agent.common.BizException;
import com.agent.tool.ToolMeta;
import com.agent.tool.ToolPermission;
import com.agent.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AppValidator 单测（docs/design/api/20260902-app-factory.md §2.4）
 * mock ToolRegistry，验证八项配置的缺项/冲突/越界规则。
 */
class AppValidatorTest {

    private ToolRegistry toolRegistry;

    @BeforeEach
    void setUp() {
        toolRegistry = Mockito.mock(ToolRegistry.class);
        Mockito.when(toolRegistry.metaOf("policy_query"))
                .thenReturn(Optional.of(new ToolMeta("policy_query", "d", "{}", ToolPermission.READ, 5000)));
        Mockito.when(toolRegistry.metaOf("book_order"))
                .thenReturn(Optional.of(new ToolMeta("book_order", "d", "{}", ToolPermission.WRITE, 5000)));
        Mockito.when(toolRegistry.metaOf("pay_order"))
                .thenReturn(Optional.of(new ToolMeta("pay_order", "d", "{}", ToolPermission.PAYMENT, 5000)));
        Mockito.when(toolRegistry.metaOf("ghost_tool")).thenReturn(Optional.empty());
    }

    private String cfg() {
        return """
                {"role":{"name":"测试应用"},
                 "prompt":{"system":"你是助手"},
                 "quota":{"maxSteps":10,"tokenBudget":32000,"timeoutMs":300000,"loopThreshold":3,"maxConcurrency":5}}
                """;
    }

    @Test
    void 合法配置_通过() {
        assertDoesNotThrow(() -> new AppValidator(toolRegistry).validate(cfg()));
    }

    @Test
    void 缺role_name_报400() {
        BizException e = assertThrows(BizException.class,
                () -> new AppValidator(toolRegistry).validate(cfg().replace("\"name\":\"测试应用\"", "\"name\":\"\"")));
        assertEquals(400, e.getCode());
        assertTrue(e.getMessage().contains("role.name"));
    }

    @Test
    void 缺prompt_system_报400() {
        BizException e = assertThrows(BizException.class,
                () -> new AppValidator(toolRegistry).validate(
                        "{\"role\":{\"name\":\"x\"},\"quota\":{\"maxSteps\":10,\"tokenBudget\":1,\"timeoutMs\":1,\"maxConcurrency\":1}}"));
        assertEquals(400, e.getCode());
        assertTrue(e.getMessage().contains("prompt.system"));
    }

    @Test
    void 缺quota_报400() {
        BizException e = assertThrows(BizException.class,
                () -> new AppValidator(toolRegistry).validate(
                        "{\"role\":{\"name\":\"x\"},\"prompt\":{\"system\":\"s\"}}"));
        assertEquals(400, e.getCode());
        assertTrue(e.getMessage().contains("quota"));
    }

    @Test
    void quota越界_报400() {
        BizException e = assertThrows(BizException.class,
                () -> new AppValidator(toolRegistry).validate(cfg().replace("\"maxSteps\":10", "\"maxSteps\":0")));
        assertTrue(e.getMessage().contains("maxSteps"));
    }

    @Test
    void 工具未注册_报400() {
        BizException e = assertThrows(BizException.class,
                () -> new AppValidator(toolRegistry).validate(cfg().replace("{\"role\"",
                        "{\"tools\":[{\"name\":\"ghost_tool\",\"permission\":\"READ\"}],\"role\"")));
        assertTrue(e.getMessage().contains("ghost_tool"));
        assertTrue(e.getMessage().contains("未注册"));
    }

    @Test
    void 白名单声明PAYMENT_报400() {
        BizException e = assertThrows(BizException.class,
                () -> new AppValidator(toolRegistry).validate(cfg().replace("{\"role\"",
                        "{\"tools\":[{\"name\":\"book_order\",\"permission\":\"PAYMENT\"}],\"role\"")));
        assertTrue(e.getMessage().contains("PAYMENT"));
    }

    @Test
    void 工具本体PAYMENT级_禁止入白名单() {
        BizException e = assertThrows(BizException.class,
                () -> new AppValidator(toolRegistry).validate(cfg().replace("{\"role\"",
                        "{\"tools\":[{\"name\":\"pay_order\",\"permission\":\"READ\"}],\"role\"")));
        assertTrue(e.getMessage().contains("pay_order"));
    }

    @Test
    void handoff阈值非法_报400() {
        BizException e = assertThrows(BizException.class,
                () -> new AppValidator(toolRegistry).validate(cfg().replace("{\"role\"",
                        "{\"handoff\":{\"enabled\":true,\"threshold\":1.5},\"role\"")));
        assertTrue(e.getMessage().contains("threshold"));
    }

    @Test
    void handoff关闭_阈值缺省_通过() {
        assertDoesNotThrow(() -> new AppValidator(toolRegistry).validate(
                cfg().replace("{\"role\"", "{\"handoff\":{\"enabled\":false},\"role\"")));
    }

    @Test
    void 配置非合法JSON_报400() {
        BizException e = assertThrows(BizException.class,
                () -> new AppValidator(toolRegistry).validate("{not json"));
        assertEquals(400, e.getCode());
    }
}