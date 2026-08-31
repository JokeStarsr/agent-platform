package com.agent.tool.tools;

import com.agent.tool.AgentTool;
import com.agent.tool.ToolPermission;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * W6 商旅 Mock 工具：下单（写操作——HITL 审批 + 幂等键由引擎统一实施）
 */
@Component
public class BookOrderTool implements AgentTool {

    @Override
    public String name() {
        return "book_order";
    }

    @Override
    public String description() {
        return "按方案下单（航班+酒店）。写操作，需人工审批，同一方案切勿重复下单。";
    }

    @Override
    public String parameters() {
        return "{\"type\":\"object\",\"properties\":{\"plan\":{\"type\":\"object\"}},\"required\":[\"plan\"]}";
    }

    @Override
    public ToolPermission permission() {
        return ToolPermission.WRITE;
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> args) {
        return Map.of("orderId", "TR-" + System.nanoTime() % 100000, "status", "BOOKED");
    }
}
