package com.agent.tool.toolmarket;

import com.agent.common.BizException;
import com.agent.tool.ParamSchemaValidator;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * L5 工具市场：注册校验器（docs/design/architecture/20260905-tool-marketplace.md §4.1）。
 * 校验项：名称格式/唯一、描述长度、parameters 合法 JSON Schema、permission 枚举、测试用例齐全。
 * Schema 校验复用 ToolEngine 同款自研解析（避免 everit 类冲突），并验证可解析为 JSON。
 */
public class ToolRegistrationValidator {

    private static final String NAME_PATTERN = "[a-z_][a-z0-9_]*";
    private static final List<String> PERMISSIONS = List.of("READ", "WRITE", "PAYMENT");
    private static final List<String> BEHAVIORS = List.of("MOCK", "ARGS_ECHO");
    private static final List<String> CATEGORIES =
            List.of("business", "communication", "data", "schedule", "payment", "utility");
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 校验注册请求；任一项不过抛 BizException(400, 原因) */
    public static void validate(ToolMarketService.ToolRegRequest req) {
        if (req.toolName() == null || !req.toolName().matches(NAME_PATTERN)) {
            throw new BizException(400, "工具名非法：须小写下划线 [a-z_][a-z0-9_]*");
        }
        if (req.description() == null || req.description().trim().length() < 20) {
            throw new BizException(400, "工具描述过短：须 ≥ 20 字符（模型靠它区分工具）");
        }
        if (req.displayName() == null || req.displayName().isBlank()) {
            throw new BizException(400, "目录展示名不能为空");
        }
        if (req.category() != null && !CATEGORIES.contains(req.category())) {
            throw new BizException(400, "分类非法：" + req.category() + "（可选 " + CATEGORIES + "）");
        }
        if (req.permission() == null || !PERMISSIONS.contains(req.permission())) {
            throw new BizException(400, "权限非法：须 READ / WRITE / PAYMENT");
        }
        if (req.behavior() != null && !BEHAVIORS.contains(req.behavior())) {
            throw new BizException(400, "行为非法：" + req.behavior() + "（可选 " + BEHAVIORS + "）");
        }
        // Schema 结构校验：复用 ToolEngine 同款解析（非法 JSON/非对象 → 拒绝）
        try {
            ParamSchemaValidator.validate(req.parameters(), Map.of());
        } catch (ParamSchemaValidator.ValidationException e) {
            throw new BizException(400, "parameters 不是合法 JSON Schema：" + e.getMessage());
        }
        validateTestcases(req.testcases());
    }

    public static void validateTestcases(List<Map<String, Object>> testcases) {
        if (testcases == null || testcases.isEmpty()) {
            throw new BizException(400, "测试用例至少 1 条（发布时执行 smoke 校验）");
        }
        for (int i = 0; i < testcases.size(); i++) {
            Map<String, Object> tc = testcases.get(i);
            if (tc.get("name") == null || String.valueOf(tc.get("name")).isBlank()) {
                throw new BizException(400, "测试用例[" + i + "].name 不能为空");
            }
            if (tc.get("expectCode") == null) {
                throw new BizException(400, "测试用例[" + i + "].expectCode 缺失");
            }
        }
    }
}