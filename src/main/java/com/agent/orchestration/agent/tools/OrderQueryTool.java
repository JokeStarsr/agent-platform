package com.agent.orchestration.agent.tools;

import com.agent.orchestration.agent.AgentTool;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * L3 编排层种子工具：查询最近订单（只读，Mock 数据，商旅/真实订单服务接入前占位演示）
 */
@Component
public class OrderQueryTool implements AgentTool {

    @Override
    public String name() {
        return "query_recent_orders";
    }

    @Override
    public String description() {
        return "查询用户最近 3 笔订单（返回订单号/商品/金额/状态），客服核实订单信息时使用";
    }

    @Override
    public String argsSchema() {
        return "{\"type\":\"object\",\"properties\":{\"userId\":{\"type\":\"string\",\"description\":\"用户ID\"}},\"required\":[\"userId\"]}";
    }

    @Override
    public boolean write() {
        return false;
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> args) {
        String userId = String.valueOf(args.getOrDefault("userId", ""));
        return Map.of(
                "userId", userId,
                "orders", List.of(
                        Map.of("orderId", "SO20260830001", "product", "智能手环", "amount", 299.0, "status", "已签收"),
                        Map.of("orderId", "SO20260818002", "product", "保温杯", "amount", 89.0, "status", "配送中"),
                        Map.of("orderId", "SO20260729003", "product", "运动鞋", "amount", 499.0, "status", "已完成")));
    }
}