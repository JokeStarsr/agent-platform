package com.agent.orchestration.appfactory;

import com.agent.capability.AppPromptProvider.AppPrompt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L3 AppPromptProvider 解析单元测试（docs/design/api/20260902-app-factory.md §2.5）
 * AC：注册应用返回渲染后的 system prompt + handoff 参数；未注册/空 appId 返回 fallback。
 */
class OrchAppPromptProviderTest {

    private OrchAppPromptProvider provider;

    @BeforeEach
    void setUp() {
        AppRegistry registry = new AppRegistry(null) {
            @Override
            public AppDefinition get(String tenantId, String appId) {
                if ("cs_customer_service".equals(appId)) {
                    return AppDefinition.parse("cs_customer_service", "企业智能客服", """
                            {"prompt":{"system":"你是{roleName}。护栏：最多 {maxSteps} 步；预算 {tokenBudget}。"},
                             "handoff":{"enabled":true,"threshold":0.4,"weights":{"retrieval":0.5,"coverage":0.3,"citation":0.2}},
                             "quota":{"maxSteps":12,"tokenBudget":40000}}
                            """).withStatus("ENABLED");
                }
                if ("no_handoff".equals(appId)) {
                    return AppDefinition.parse("no_handoff", "无转人工", """
                            {"prompt":{"system":"固定提示词。"},"handoff":{"enabled":false}}
                            """).withStatus("ENABLED");
                }
                return null;
            }
        };
        provider = new OrchAppPromptProvider(registry);
    }

    @Test
    void 注册应用_返回渲染提示词与转人工参数() {
        AppPrompt p = provider.resolve("t1", "cs_customer_service");
        assertTrue(p.systemPrompt().contains("你是"));
        // 占位符 {roleName} 未提供时原样保留；{maxSteps}/{tokenBudget} 已替换
        assertTrue(p.systemPrompt().contains("最多 12 步"));
        assertTrue(p.systemPrompt().contains("预算 40000"));
        assertTrue(p.handoffEnabled());
        assertEquals(0.4, p.handoffThreshold());
        assertEquals(0.5, ((Number) p.handoffWeights().get("retrieval")).doubleValue());
    }

    @Test
    void 未启用转人工_返回false与null阈值() {
        AppPrompt p = provider.resolve("t1", "no_handoff");
        assertFalse(p.handoffEnabled());
        assertNull(p.handoffThreshold());
        assertNull(p.handoffWeights());
        assertEquals("固定提示词。", p.systemPrompt());
    }

    @Test
    void 未注册应用_返回fallback() {
        AppPrompt p = provider.resolve("t1", "unknown_app");
        assertEquals("", p.systemPrompt());
        assertFalse(p.handoffEnabled());
    }

    @Test
    void 空appId_返回fallback() {
        AppPrompt p = provider.resolve("t1", "");
        assertEquals("", p.systemPrompt());
    }
}