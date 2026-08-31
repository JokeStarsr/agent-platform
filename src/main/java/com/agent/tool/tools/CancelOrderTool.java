package com.agent.tool.tools;

import com.agent.tool.AgentTool;
import com.agent.tool.ToolPermission;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * W6 商旅 Mock 工具：取消订单（写——补偿回滚钩子）
 * 由引擎在 book_order 的 rollbackTool 引用；幂等键复用下单原键（防重复补偿）。
 */
@Component
public class CancelOrderTool implements AgentTool {

    @Override
    public String name() {
        return "cancel_order";
    }

    @Override
    public String description() {
        return "取消已下单的行程（补偿回滚用）。幂等键复用下单原键。";
    }

    @Override
    public String parameters() {
        return "{\"type\":\"object\",\"properties\":{\"orderId\":{\"type\":\"string\"}},\"required\":[\"orderId\"]}";
    }

    @Override
    public ToolPermission permission() {
        return ToolPermission.WRITE;
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> args) {
        return Map.of("cancelled", true, "orderId", String.valueOf(args.getOrDefault("orderId", "?")));
    }
}
