package com.agent.orchestration.appfactory;

import com.agent.capability.AppPromptProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * L3 编排层：AppPromptProvider 实现（docs/design/api/20260902-app-factory.md §2.5）
 * 解析应用配置 → 渲染 system prompt + 提取转人工参数，供 L4 RagService/ContextController 使用。
 */
@Component
public class OrchAppPromptProvider implements AppPromptProvider {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final AppRegistry registry;

    public OrchAppPromptProvider(AppRegistry registry) {
        this.registry = registry;
    }

    @Override
    public AppPrompt resolve(String tenantId, String appId) {
        if (appId == null || appId.isBlank()) {
            return AppPrompt.fallback();
        }
        AppDefinition d = registry.get(tenantId, appId);
        if (d == null) {
            return AppPrompt.fallback();
        }

        // 渲染 system prompt：补充 {toolsJson} 占位符（tr_booking seed 用到）
        Map<String, Object> vars = new LinkedHashMap<>();
        AppDefinition.Quota q = d.quota();
        if (q != null) {
            vars.put("maxSteps", q.maxSteps());
            vars.put("tokenBudget", q.tokenBudget());
        }
        if (!d.tools().isEmpty()) {
            try {
                vars.put("toolsJson", JSON.writeValueAsString(d.tools().stream()
                        .map(t -> Map.of("name", t.name(), "permission", t.permission()))
                        .toList()));
            } catch (Exception ignored) { }
        }
        String system = (d.prompt() != null && d.prompt().system() != null)
                ? PromptCenter.render(d.prompt().system(), vars) : "";

        // 转人工参数
        AppDefinition.Handoff h = d.handoff();
        boolean handoffEnabled = h != null && Boolean.TRUE.equals(h.enabled());

        return new AppPrompt(system, handoffEnabled,
                handoffEnabled ? h.threshold() : null,
                handoffEnabled ? h.weights() : null);
    }
}
