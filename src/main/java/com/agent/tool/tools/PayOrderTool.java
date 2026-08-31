package com.agent.tool.tools;

import com.agent.tool.AgentTool;
import com.agent.tool.ToolPermission;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * W8 支付工具——以 PAYMENT 权限注册（docs/design/architecture/20260901-w8-trip-scenario.md §2.4）
 * W5 决议：PAYMENT 仅注册不放开（引擎硬 403），双人复核留 P2 末期。
 * <p>硬保证：任何路径调用 pay_order 均被 ToolEngine 以 403 拒绝 → 支付无人工确认不可达（工具层保证，非编排约定）。</p>
 */
@Component
public class PayOrderTool implements AgentTool {

    @Override
    public String name() {
        return "pay_order";
    }

    @Override
    public String description() {
        return "支付订单（PAYMENT 级：本期未放开，双人复核后启用）。变更预览要素：API=pay、参数=订单金额/账户、影响范围=扣款。";
    }

    @Override
    public String parameters() {
        return "{\"type\":\"object\",\"properties\":{\"orderId\":{\"type\":\"string\"},\"amount\":{\"type\":\"number\"}},\"required\":[\"orderId\"]}";
    }

    @Override
    public ToolPermission permission() {
        return ToolPermission.PAYMENT;
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> args) {
        throw new IllegalStateException("PAYMENT 权限未放开，引擎已拦截（本方法不可达）");
    }
}