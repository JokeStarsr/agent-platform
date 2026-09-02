package com.agent.orchestration.agent;

import com.agent.common.BizException;
import com.agent.data.agentrun.AgentRunRepository;
import com.agent.model.llm.LlmGateway;
import com.agent.orchestration.appfactory.AppDefinition;
import com.agent.orchestration.appfactory.AppRegistry;
import com.agent.tool.ToolEngineService;
import com.agent.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1 二期：AgentRuntime 应用工厂消费（docs/design/api/20260902-app-factory.md §2.5）
 * AC-1 SUSPENDED 应用提交 → 409；AC-2 config==null 时用应用配额装配。
 */
class AgentRuntimeAppFactoryTest {

    private AgentRunRepository repo;
    private AgentRuntimeServiceImpl cut;

    @BeforeEach
    void setUp() {
        repo = mock(AgentRunRepository.class);
        when(repo.countRunning(anyString())).thenReturn(0);
        LlmGateway llm = mock(LlmGateway.class);
        ToolEngineService toolEngine = mock(ToolEngineService.class);
        ToolRegistry toolRegistry = new ToolRegistry(List.of());
        AppRegistry registry = mock(AppRegistry.class);
        when(registry.get(eq("t1"), eq("cs_suspended")))
                .thenReturn(AppDefinition.parse("cs_suspended", "停用应用", "{}").withStatus("SUSPENDED"));
        when(registry.get(eq("t1"), eq("cs_customer_service")))
                .thenReturn(AppDefinition.parse("cs_customer_service", "客服应用", """
                        {"quota":{"maxSteps":12,"tokenBudget":48000,"timeoutMs":600000,"loopThreshold":4,"maxConcurrency":3}}
                        """).withStatus("ENABLED"));
        cut = new AgentRuntimeServiceImpl(repo, llm, toolEngine, toolRegistry, registry);
    }

    @Test
    void AC1_SUSPENDED应用提交_409停止() {
        BizException e = assertThrows(BizException.class,
                () -> cut.submit("t1", "cs_suspended", "测试任务", null));
        assertEquals(409, e.getCode());
    }

    @Test
    void AC2_config为空_应用配额装配进createRun() {
        cut.submit("t1", "cs_customer_service", "测试任务", null);
        verify(repo).createRun(eq("t1"), eq("cs_customer_service"), eq("测试任务"), anyString(),
                eq(12), eq(48000), eq(600000), eq(4), anyString());
    }
}