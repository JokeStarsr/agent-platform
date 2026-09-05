package com.agent.tool.toolmarket;

import com.agent.tool.AgentTool;
import com.agent.tool.ToolPermission;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * L5 工具市场：动态工具执行器（目录 → AgentTool 桥接）。
 * <p>它把 {@code t_tool_catalog} 的一行（自注册/出站）适配为 ToolEngine 可执行的
 * {@code AgentTool}，使 ToolEngine（幂等/Schema 校验/超时/PAYMENT 403）无需感知工具来源。</p>
 * <p>v1 行为：注册时附 {@code behavior} 决定 execute 结果——</p>
 * <ul>
 *   <li>{@code MOCK}：返回测试用例第一条的仿真结果（或固定 ok），用于演示"注册→发布→可调用"闭环</li>
 *   <li>{@code ARGS_ECHO}：回显参数（调试用）</li>
 * </ul>
 * <p>真实实现体（HTTP 转发/沙箱加载）留 W12 沙箱 / W14 SkillHub。</p>
 */
public class DynamicAgentTool implements AgentTool {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String name;
    private final String description;
    private final String parameters;
    private final ToolPermission permission;
    private final long timeoutMs;
    private final String behavior;
    private final List<Map<String, Object>> testcases;

    public DynamicAgentTool(String name, String description, String parameters, String permission,
                            long timeoutMs, String behavior, List<Map<String, Object>> testcases) {
        this.name = name;
        this.description = description;
        this.parameters = parameters;
        this.permission = ToolPermission.valueOf(permission == null ? "READ" : permission);
        this.timeoutMs = timeoutMs;
        this.behavior = behavior == null ? "ARGS_ECHO" : behavior;
        this.testcases = testcases == null ? List.of() : testcases;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public String parameters() {
        return parameters;
    }

    @Override
    public ToolPermission permission() {
        return permission;
    }

    @Override
    public long timeoutMs() {
        return timeoutMs;
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> args) {
        if ("MOCK".equals(behavior)) {
            // 返回测试用例预期结果（演示闭环用）；无测试时回显 ok
            if (!testcases.isEmpty()) {
                Map<String, Object> tc = testcases.get(0);
                Object expect = tc.get("expectResult");
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("mock", true);
                out.put("echoRequestId", args.getOrDefault("requestId", "-"));
                if (expect instanceof Map<?, ?> m) {
                    out.putAll(toStringObjectMap(m));
                } else if (expect != null) {
                    out.put("result", expect);
                } else {
                    out.put("result", "ok");
                }
                return out;
            }
            return Map.of("mock", true, "ok", true);
        }
        // ARGS_ECHO：回显参数（调试）
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("echo", args);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toStringObjectMap(Map<?, ?> m) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            result.put(String.valueOf(e.getKey()), e.getValue());
        }
        return result;
    }
}