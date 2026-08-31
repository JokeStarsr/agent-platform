package com.agent.tool.tools;

import com.agent.tool.AgentTool;
import com.agent.tool.ToolPermission;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * L5 种子工具：订单部分退款（写操作演示，金额上限由参数 Schema 校验拦截）
 * 参数校验（minimum/maximum）由 ToolEngine 统一执行，模型参数幻觉在此入口被拦。
 */
@Component
public class RefundOrderTool implements AgentTool {

    @Override
    public String name() {
        return "refund_order_partial";
    }

    @Override
    public String description() {
        return "对已签收订单执行部分退款，退款金额 0-500 元（演示工具），退款前务必核实订单可退金额，同一订单同一金额不可重复退款";
    }

    @Override
    public String parameters() {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"orderId\":{\"type\":\"string\",\"description\":\"订单号\"},"
                + "\"refundAmount\":{\"type\":\"number\",\"minimum\":0,\"maximum\":500,\"description\":\"退款金额(元)\"}"
                + "},\"required\":[\"orderId\",\"refundAmount\"]}";
    }

    @Override
    public ToolPermission permission() {
        return ToolPermission.WRITE;
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> args) {
        double amount = ((Number) args.get("refundAmount")).doubleValue();
        return Map.of("result", "ok", "orderId", String.valueOf(args.get("orderId")),
                "refunded", amount, "status", "退款处理中");
    }
}