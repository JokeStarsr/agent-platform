package com.agent.orchestration.appfactory;

import java.util.Map;

/**
 * L3 编排层：Prompt 中心（P2 铁律"Prompt 不得硬编码"的收口位置）
 * v1：占位符渲染 + 内置默认模板注册（app 级 Prompt 存 t_app.config_json，见表设计 §10 演进）。
 */
public final class PromptCenter {

    private static final Map<String, String> DEFAULTS = Map.of(
            "workflow-llm-node", "你是工作流中的 LLM 生成节点，严格按要求输出。");

    private PromptCenter() {
    }

    /** 默认模板（工作流内部语义等非八项可配处） */
    public static String defaultPrompt(String key) {
        return DEFAULTS.getOrDefault(key, "");
    }

    /** 占位符渲染：将模板中的 {name} 替换为 vars 对应值（未提供的占位符原样保留） */
    public static String render(String template, Map<String, Object> vars) {
        if (template == null || vars == null || vars.isEmpty()) {
            return template;
        }
        String out = template;
        for (Map.Entry<String, Object> e : vars.entrySet()) {
            String v = e.getValue() == null ? "" : String.valueOf(e.getValue());
            out = out.replace("{" + e.getKey() + "}", v);
        }
        return out;
    }
}