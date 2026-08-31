package com.agent.tool.tools;

import com.agent.tool.AgentTool;
import com.agent.tool.ToolPermission;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * L5 种子工具：发放补偿优惠券（写操作——HITL 审批 + 幂等键均由 Runtime/引擎统一实施）
 */
@Component
public class SendCouponTool implements AgentTool {

    @Override
    public String name() {
        return "send_coupon";
    }

    @Override
    public String description() {
        return "向用户发放一张补偿优惠券（5元无门槛），仅客服确认补偿方案后调用，同一客户同一原因切勿重复调用";
    }

    @Override
    public String parameters() {
        return "{\"type\":\"object\",\"properties\":{\"userId\":{\"type\":\"string\"}},\"required\":[\"userId\"]}";
    }

    @Override
    public ToolPermission permission() {
        return ToolPermission.WRITE;
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> args) {
        return Map.of("result", "ok", "coupon", "5元无门槛券已发放至账户", "userId", String.valueOf(args.get("userId")));
    }
}