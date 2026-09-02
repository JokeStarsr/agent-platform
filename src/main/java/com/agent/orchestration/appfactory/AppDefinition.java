package com.agent.orchestration.appfactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * L3 编排层：应用定义（t_app.config_json 的解析视图，八项配置）
 * 对应 docs/design/api/20260902-app-factory.md §2.2；未知项回退 null/空（消费点自行兜底现状）。
 */
public class AppDefinition {

    private static final ObjectMapper JSON = new ObjectMapper();

    public record Role(String name, String description) {
    }

    public record Prompt(String system, String userTemplate, Integer contextBudget) {
    }

    public record Kb(String kbType, String sourcePrefix) {
    }

    public record ToolItem(String name, String permission) {
    }

    public record Memory(Boolean enabled, List<String> fieldWhitelist) {
    }

    public record Eval(String ref, Map<String, Object> metrics) {
    }

    public record Handoff(Boolean enabled, Double threshold, Map<String, Object> weights) {
    }

    public record Quota(int maxSteps, int tokenBudget, int timeoutMs, int loopThreshold, int maxConcurrency) {
    }

    private final String appId;
    private final String name;
    private final Role role;
    private final Prompt prompt;
    private final Kb kb;
    private final List<ToolItem> tools;
    private final Memory memory;
    private final Eval eval;
    private final Handoff handoff;
    private final Quota quota;
    private final String rawConfigJson;

    public AppDefinition(String appId, String name, Role role, Prompt prompt, Kb kb,
                         List<ToolItem> tools, Memory memory, Eval eval, Handoff handoff,
                         Quota quota, String rawConfigJson) {
        this.appId = appId;
        this.name = name;
        this.role = role;
        this.prompt = prompt;
        this.kb = kb;
        this.tools = tools == null ? List.of() : List.copyOf(tools);
        this.memory = memory;
        this.eval = eval;
        this.handoff = handoff;
        this.quota = quota;
        this.rawConfigJson = rawConfigJson;
    }

    /** 从 config_json 解析（容忍缺项：缺项留 null，由校验/消费兜底） */
    public static AppDefinition parse(String appId, String name, String configJson) {
        try {
            JsonNode root = JSON.readTree(configJson);
            Role role = null;
            JsonNode r = root.path("role");
            if (!r.isMissingNode()) {
                role = new Role(text(r.get("name")), text(r.get("description")));
            }
            Prompt prompt = null;
            JsonNode pr = root.path("prompt");
            if (!pr.isMissingNode()) {
                prompt = new Prompt(text(pr.get("system")), text(pr.get("userTemplate")),
                        pr.has("contextBudget") ? pr.get("contextBudget").asInt() : null);
            }
            Kb kb = null;
            JsonNode kbNode = root.path("kb");
            if (!kbNode.isMissingNode()) {
                kb = new Kb(text(kbNode.get("kbType")), text(kbNode.get("sourcePrefix")));
            }
            List<ToolItem> tools = new ArrayList<>();
            JsonNode t = root.path("tools");
            if (t.isArray()) {
                for (JsonNode item : t) {
                    tools.add(new ToolItem(text(item.get("name")), text(item.get("permission"))));
                }
            }
            Memory memory = null;
            JsonNode m = root.path("memory");
            if (!m.isMissingNode()) {
                List<String> whitelist = new ArrayList<>();
                if (m.path("fieldWhitelist").isArray()) {
                    m.path("fieldWhitelist").forEach(n -> whitelist.add(n.asText()));
                }
                memory = new Memory(m.has("enabled") ? m.get("enabled").asBoolean() : null, whitelist);
            }
            Eval eval = null;
            JsonNode ev = root.path("eval");
            if (!ev.isMissingNode()) {
                Map<String, Object> metrics = JSON.convertValue(ev.path("metrics"), Map.class);
                eval = new Eval(text(ev.get("ref")), metrics);
            }
            Handoff handoff = null;
            JsonNode h = root.path("handoff");
            if (!h.isMissingNode()) {
                Map<String, Object> weights = JSON.convertValue(h.path("weights"), Map.class);
                handoff = new Handoff(h.has("enabled") ? h.get("enabled").asBoolean() : null,
                        h.has("threshold") ? h.get("threshold").asDouble() : null, weights);
            }
            Quota quota = null;
            JsonNode q = root.path("quota");
            if (!q.isMissingNode()) {
                quota = new Quota(q.path("maxSteps").asInt(10), q.path("tokenBudget").asInt(32000),
                        q.path("timeoutMs").asInt(300000), q.path("loopThreshold").asInt(3),
                        q.path("maxConcurrency").asInt(5));
            }
            return new AppDefinition(appId, name, role, prompt, kb, tools, memory, eval, handoff, quota, configJson);
        } catch (Exception e) {
            throw new IllegalStateException("应用配置解析失败: " + e.getMessage(), e);
        }
    }

    private static String text(JsonNode n) {
        return n == null || n.isNull() || n.isMissingNode() ? null : n.asText();
    }

    public String appId() {
        return appId;
    }

    public String name() {
        return name;
    }

    public Role role() {
        return role;
    }

    public Prompt prompt() {
        return prompt;
    }

    public Kb kb() {
        return kb;
    }

    public List<ToolItem> tools() {
        return tools;
    }

    public Memory memory() {
        return memory;
    }

    public Eval eval() {
        return eval;
    }

    public Handoff handoff() {
        return handoff;
    }

    public Quota quota() {
        return quota;
    }

    public String rawConfigJson() {
        return rawConfigJson;
    }
}