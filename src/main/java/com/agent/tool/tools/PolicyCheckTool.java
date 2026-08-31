package com.agent.tool.tools;

import com.agent.common.BizException;
import com.agent.data.policy.PolicyRuleRepository;
import com.agent.data.policy.PolicyRuleRepository.PolicyRule;
import com.agent.tool.AgentTool;
import com.agent.tool.ToolPermission;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * W8 差旅政策校验工具（L5，READ）——确定性规则引擎式校验（docs/design/architecture/20260901-w8-trip-scenario.md §2.2）
 * <p>政策违规 100% 被拦截：任一规则命中即抛 BizException(400 POLICY_VIOLATION) → Workflow 节点 FAILED → 流程 FAILED。
 * 不依赖 LLM 自律；金额超阈值标 needsApproval=true（转人工审批语义，不失败）。</p>
 */
@Component
public class PolicyCheckTool implements AgentTool {

    private final PolicyRuleRepository ruleRepo;

    public PolicyCheckTool(PolicyRuleRepository ruleRepo) {
        this.ruleRepo = ruleRepo;
    }

    @Override
    public String name() {
        return "policy_check";
    }

    @Override
    public String description() {
        return "差旅政策合规校验：按租户规则校验方案（舱位/酒店星级/提前天数/总金额）。违规即拒绝（POLICY_VIOLATION），金额超阈值置 needsApproval。";
    }

    @Override
    public String parameters() {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"flightClass\":{\"type\":\"string\"},"
                + "\"hotelStar\":{\"type\":\"number\"},"
                + "\"bookAheadDays\":{\"type\":\"number\"},"
                + "\"totalAmount\":{\"type\":\"number\"}},"
                + "\"required\":[]}";
    }

    @Override
    public ToolPermission permission() {
        return ToolPermission.READ;
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> args) {
        // v1 工具上下文无租户头透传，租户从调用上下文注入（Workflow 场景固定 default；契约测试直接验证规则语义）
        // 说明：多租户真实隔离需引擎按 X-Tenant-Id 注入租户——v1 固定 default 与种子规则一致
        String tenantId = "default";
        List<Map<String, Object>> violations = new ArrayList<>();
        boolean needsApproval = false;

        String flightClass = str(args.get("flightClass"));
        if (flightClass != null) {
            for (PolicyRule r : ruleRepo.rulesByDimension(tenantId, "flight_class")) {
                if (failure(r, flightClass)) {
                    violations.add(violationOf(r));
                }
            }
        }
        if (isNumber(args.get("hotelStar"))) {
            double star = num(args.get("hotelStar"));
            for (PolicyRule r : ruleRepo.rulesByDimension(tenantId, "hotel_star")) {
                double t = thresholdNum(r);
                if (t < 0) {
                    continue;
                }
                if ("le".equals(r.operator()) && star > t) {
                    violations.add(violationOf(r));
                }
                if ("ge".equals(r.operator()) && star < t) {
                    violations.add(violationOf(r));
                }
            }
        }
        if (isNumber(args.get("bookAheadDays"))) {
            double days = num(args.get("bookAheadDays"));
            for (PolicyRule r : ruleRepo.rulesByDimension(tenantId, "book_ahead")) {
                double t = thresholdNum(r);
                if (t >= 0 && "ge".equals(r.operator()) && days < t) {
                    violations.add(violationOf(r));
                }
            }
        }
        if (isNumber(args.get("totalAmount"))) {
            double amount = num(args.get("totalAmount"));
            for (PolicyRule r : ruleRepo.rulesByDimension(tenantId, "amount")) {
                double t = thresholdNum(r);
                if (t < 0) {
                    continue;
                }
                if ("le".equals(r.operator()) && amount > t) {
                    // 金额超限 → 需审批（人工确认），不视为硬违规
                    needsApproval = true;
                }
            }
        }

        if (!violations.isEmpty()) {
            throw new BizException(400, "POLICY_VIOLATION: " + violations);
        }
        return Map.of("compliant", true, "needsApproval", needsApproval, "violations", List.of());
    }

    private boolean failure(PolicyRule r, String value) {
        return switch (r.operator()) {
            case "in" -> !List.of(r.thresholdValue().split("[,/、]")).contains(value);
            case "deny" -> r.thresholdValue().contains(value);
            default -> false;
        };
    }

    private Map<String, Object> violationOf(PolicyRule r) {
        return Map.of("ruleCode", r.ruleCode(), "message", r.message());
    }

    private static String threshold(PolicyRule r) {
        return r.thresholdValue() == null ? "" : r.thresholdValue().trim();
    }

    private static boolean isNumber(Object v) {
        return v instanceof Number;
    }

    private static double num(Object v) {
        return v instanceof Number n ? n.doubleValue() : -1;
    }

    private static double thresholdNum(PolicyRule r) {
        try {
            return Double.parseDouble(threshold(r));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}