package com.agent.orchestration.appfactory;

import com.agent.orchestration.agent.AgentConfig;
import com.agent.orchestration.appfactory.AppDefinition.Quota;

import java.util.List;
import java.util.Map;

/**
 * L3 编排层：应用定义 → 消费侧产物装配（docs/design/api/20260902-app-factory.md §2.2）
 * 未知应用/缺项一律回退现状语义，不破坏既有行为。
 */
public final class AppAssembler {

    private AppAssembler() {
    }

    /** 配额装配：app 有 quota 用 app 的，否则回退 AgentConfig.of(appId) 场景默认 */
    public static AgentConfig toAgentConfig(AppDefinition d, String appId) {
        Quota q = d == null ? null : d.quota();
        if (q == null) {
            return AgentConfig.of(appId);
        }
        return new AgentConfig(q.maxSteps(), q.tokenBudget(), q.timeoutMs(), q.loopThreshold(), q.maxConcurrency());
    }

    /** 渲染系统 Prompt（占位符替换：{maxSteps}/{tokenBudget}/{toolsJson} 等） */
    public static String renderSystemPrompt(AppDefinition d) {
        if (d == null || d.prompt() == null || d.prompt().system() == null) {
            return "";
        }
        Map<String, Object> vars = Map.of();
        Quota q = d.quota();
        if (q != null) {
            vars = new java.util.LinkedHashMap<>(vars);
            vars.put("maxSteps", q.maxSteps());
            vars.put("tokenBudget", q.tokenBudget());
        }
        return PromptCenter.render(d.prompt().system(), vars);
    }

    /** 渲染用户 Prompt 模板（{task}/{history}/{query} 等由调用方提供） */
    public static String renderUserPrompt(AppDefinition d, Map<String, Object> vars) {
        if (d == null || d.prompt() == null || d.prompt().userTemplate() == null) {
            return "";
        }
        return PromptCenter.render(d.prompt().userTemplate(), vars);
    }

    /** 应用工具白名单（工具名列表；空 = 无白名单限制） */
    public static List<String> toolWhitelist(AppDefinition d) {
        return d == null ? List.of() : d.tools().stream().map(AppDefinition.ToolItem::name).toList();
    }

    /** 转人工参数（threshold + 权重），应用未启用 handoff 返回 null */
    public static Map<String, Object> handoffParams(AppDefinition d) {
        if (d == null || d.handoff() == null || !Boolean.TRUE.equals(d.handoff().enabled())) {
            return null;
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("threshold", d.handoff().threshold());
        out.put("weights", d.handoff().weights());
        return out;
    }
}