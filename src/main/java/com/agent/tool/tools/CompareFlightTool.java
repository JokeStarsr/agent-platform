package com.agent.tool.tools;

import com.agent.tool.AgentTool;
import com.agent.tool.ToolPermission;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * W6 商旅 Mock 工具：航班比价（只读）
 */
@Component
public class CompareFlightTool implements AgentTool {

    @Override
    public String name() {
        return "compare_flight";
    }

    @Override
    public String description() {
        return "按城市与天数比对可选航班（Mock 返回固定两档价格）。商旅流程比价环节。";
    }

    @Override
    public String parameters() {
        return "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"},\"days\":{\"type\":\"integer\"}},\"required\":[\"city\"]}";
    }

    @Override
    public ToolPermission permission() {
        return ToolPermission.READ;
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> args) {
        return Map.of("flights", List.of(
                Map.of("id", "CA101", "price", 1200, "duration", "2h30m"),
                Map.of("id", "MU202", "price", 980, "duration", "2h45m")));
    }
}
