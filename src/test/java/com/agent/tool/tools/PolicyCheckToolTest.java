package com.agent.tool.tools;

import com.agent.common.BizException;
import com.agent.data.policy.PolicyRuleRepository;
import com.agent.data.policy.PolicyRuleRepository.PolicyRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 政策校验工具单测（docs/design/architecture/20260901-w8-trip-scenario.md §7 AC-1 政策违规 100% 拦截）
 */
class PolicyCheckToolTest {

    private PolicyRuleRepository ruleRepo;
    private PolicyCheckTool cut;

    @BeforeEach
    void setUp() {
        ruleRepo = mock(PolicyRuleRepository.class);
        when(ruleRepo.rulesByDimension(anyString(), anyString())).thenAnswer(inv -> switch ((String) inv.getArgument(1)) {
            case "flight_class" -> List.of(new PolicyRule(1, "default", "FLIGHT_CLASS", "flight_class", "in", "经济舱", "仅允许经济舱", true));
            case "hotel_star" -> List.of(new PolicyRule(2, "default", "HOTEL_STAR", "hotel_star", "le", "3", "不超过3星", true));
            case "book_ahead" -> List.of(new PolicyRule(3, "default", "BOOK_AHEAD", "book_ahead", "ge", "2", "提前2天", true));
            case "amount" -> List.of(new PolicyRule(4, "default", "AMOUNT_LIMIT", "amount", "le", "5000.00", "不超5000元", true));
            default -> List.of();
        });
        cut = new PolicyCheckTool(ruleRepo);
    }

    @Test
    void 合规方案_放行() {
        Map<String, Object> r = cut.execute(Map.of(
                "flightClass", "经济舱", "hotelStar", 3.0, "bookAheadDays", 2.0, "totalAmount", 3000.0));
        assertEquals(Boolean.TRUE, r.get("compliant"));
        assertEquals(Boolean.FALSE, r.get("needsApproval"));
        assertEquals(List.of(), r.get("violations"));
    }

    @Test
    void 舱位违规_抛POLICY_VIOLATION() {
        BizException e = assertThrows(BizException.class,
                () -> cut.execute(Map.of("flightClass", "头等舱")));
        assertTrue(e.getMessage().contains("POLICY_VIOLATION"), e.getMessage());
        assertTrue(e.getMessage().contains("FLIGHT_CLASS"), e.getMessage());
    }

    @Test
    void 星级超标_抛POLICY_VIOLATION() {
        BizException e = assertThrows(BizException.class,
                () -> cut.execute(Map.of("hotelStar", 5.0)));
        assertTrue(e.getMessage().contains("HOTEL_STAR"), e.getMessage());
    }

    @Test
    void 提前天数不足_抛POLICY_VIOLATION() {
        BizException e = assertThrows(BizException.class,
                () -> cut.execute(Map.of("bookAheadDays", 1.0)));
        assertTrue(e.getMessage().contains("BOOK_AHEAD"), e.getMessage());
    }

    @Test
    void 金额超限_不失败_标记needsApproval() {
        Map<String, Object> r = cut.execute(Map.of("flightClass", "经济舱", "totalAmount", 8000.0));
        assertEquals(Boolean.TRUE, r.get("compliant"));
        assertEquals(Boolean.TRUE, r.get("needsApproval"), "金额超阈值应转人工审批而非直接失败");
    }
}