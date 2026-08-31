package com.agent.tool.tools;

import com.agent.tool.AgentTool;
import com.agent.tool.ToolPermission;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * W6 商旅 Mock 工具：出差政策查询（只读）
 */
@Component
public class PolicyQueryTool implements AgentTool {

    @Override
    public String name() {
        return "policy_query";
    }

    @Override
    public String description() {
        return "查询某城市出差政策（住宿/交通标准）。商旅流程第一步。";
    }

    @Override
    public String parameters() {
        return "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}},\"required\":[\"city\"]}";
    }

    @Override
    public ToolPermission permission() {
        return ToolPermission.READ;
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> args) {
        String city = String.valueOf(args.getOrDefault("city", "北京"));
        return Map.of(
                "city", city,
                "hotelStandard", 500,
                "flightClass", "经济舱",
                "dailyAllowance", 200);
    }
}
