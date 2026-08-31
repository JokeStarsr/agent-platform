package com.agent.tool.tools;

import com.agent.tool.AgentTool;
import com.agent.tool.ToolPermission;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * W6 商旅 Mock 工具：酒店比价（只读）
 */
@Component
public class CompareHotelTool implements AgentTool {

    @Override
    public String name() {
        return "compare_hotel";
    }

    @Override
    public String description() {
        return "按城市与天数比对可选酒店（Mock 返回固定两档价格）。商旅流程比价环节。";
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
        return Map.of("hotels", List.of(
                Map.of("id", "HJ-01", "price", 460, "name", "城市中心酒店"),
                Map.of("id", "HJ-02", "price", 380, "name", "快捷酒店")));
    }
}
