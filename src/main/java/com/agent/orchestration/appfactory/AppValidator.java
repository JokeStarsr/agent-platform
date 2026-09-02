package com.agent.orchestration.appfactory;

import com.agent.common.BizException;
import com.agent.orchestration.appfactory.AppDefinition.Handoff;
import com.agent.orchestration.appfactory.AppDefinition.Quota;
import com.agent.orchestration.appfactory.AppDefinition.ToolItem;
import com.agent.tool.ToolRegistry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * L3 编排层：应用配置校验器（创建/启用时全量校验，docs/design/api/20260902-app-factory.md §2.4）
 * 缺项/冲突提前报错，不落库。
 */
@Component
public class AppValidator {

    private static final List<String> ALLOWED_TOOL_PERMS = List.of("READ", "WRITE");

    private final ToolRegistry toolRegistry;

    public AppValidator(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /** 校验 config_json，失败抛 BizException(400)；成功静默 */
    public void validate(String configJson) {
        List<String> errors = new ArrayList<>();
        AppDefinition d;
        try {
            d = AppDefinition.parse("_", "_", configJson);
        } catch (Exception e) {
            throw new BizException(400, "应用配置不是合法 JSON: " + e.getMessage());
        }

        if (d.role() == null || isBlank(d.role().name())) {
            errors.add("role.name 必填");
        }
        if (d.prompt() == null || isBlank(d.prompt().system())) {
            errors.add("prompt.system 必填");
        }
        Quota q = d.quota();
        if (q == null) {
            errors.add("quota 必填");
        } else {
            if (q.maxSteps() < 1 || q.maxSteps() > 100) errors.add("quota.maxSteps 需在 [1,100]");
            if (q.tokenBudget() <= 0) errors.add("quota.tokenBudget 必须为正");
            if (q.timeoutMs() <= 0) errors.add("quota.timeoutMs 必须为正");
            if (q.maxConcurrency() < 1 || q.maxConcurrency() > 50) errors.add("quota.maxConcurrency 需在 [1,50]");
        }
        for (ToolItem t : d.tools()) {
            if (t.name() == null) {
                errors.add("工具项缺少 name");
                continue;
            }
            var meta = toolRegistry.metaOf(t.name());
            if (meta.isEmpty()) {
                errors.add("工具未注册: " + t.name());
                continue;
            }
            if (meta.get().permission() == com.agent.tool.ToolPermission.PAYMENT) {
                errors.add("工具 " + t.name() + " 本身是 PAYMENT 级（支付硬门），不允许进应用白名单");
                continue;
            }
            String perm = t.permission();
            if ("PAYMENT".equalsIgnoreCase(perm)) {
                errors.add("工具 " + t.name() + " 不允许声明 PAYMENT（支付级恒锁，白名单只到 WRITE）");
            } else if (perm == null || !ALLOWED_TOOL_PERMS.contains(perm)) {
                errors.add("工具 " + t.name() + " 权限非法（须 READ/WRITE）: " + perm);
            }
        }
        Handoff h = d.handoff();
        if (h != null && Boolean.TRUE.equals(h.enabled())) {
            if (h.threshold() == null || h.threshold() <= 0 || h.threshold() > 1) {
                errors.add("handoff.threshold 需在 (0,1]");
            }
        }

        if (!errors.isEmpty()) {
            throw new BizException(400, "应用配置校验失败: " + String.join("; ", errors));
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}