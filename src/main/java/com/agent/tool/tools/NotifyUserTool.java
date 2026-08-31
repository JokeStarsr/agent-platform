package com.agent.tool.tools;

import com.agent.tool.AgentTool;
import com.agent.tool.ToolPermission;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * W6 商旅 Mock 工具：通知用户（写——下单后通知，无回滚钩子）
 */
@Component
public class NotifyUserTool implements AgentTool {

    @Override
    public String name() {
        return "notify_user";
    }

    @Override
    public String description() {
        return "向出差人发送行程/订单通知（短信/App 推送）。商旅流程末段。";
    }

    @Override
    public String parameters() {
        return "{\"type\":\"object\",\"properties\":{\"orderId\":{\"type\":\"string\"},\"message\":{\"type\":\"string\"}},\"required\":[\"orderId\"]}";
    }

    @Override
    public ToolPermission permission() {
        return ToolPermission.WRITE;
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> args) {
        return Map.of("notified", true, "orderId", String.valueOf(args.getOrDefault("orderId", "?")));
    }
}
