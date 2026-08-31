package com.agent.orchestration.agent.tools;

import com.agent.orchestration.agent.AgentTool;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * L3 编排层种子工具：发放优惠券（写操作，触发 HITL 人工审批）
 * 演示写操作护栏：Runtime 在 write()=true 的工具执行前挂起等待审批。
 */
@Component
public class SendCouponTool implements AgentTool {

    @Override
    public String name() {
        return "send_coupon";
    }

    @Override
    public String description() {
        return "向用户发放一张补偿优惠券（5元无门槛），仅客服确认补偿方案后调用";
    }

    @Override
    public String argsSchema() {
        return "{\"type\":\"object\",\"properties\":{\"userId\":{\"type\":\"string\"},\"couponId\":{\"type\":\"string\"}},\"required\":[\"userId\",\"couponId\"]}";
    }

    @Override
    public boolean write() {
        return true;
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> args) {
        return Map.of("result", "ok", "coupon", "5元无门槛券已发放至账户", "userId", String.valueOf(args.get("userId")));
    }
}