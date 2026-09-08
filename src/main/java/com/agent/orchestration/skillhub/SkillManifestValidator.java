package com.agent.orchestration.skillhub;

import com.agent.common.BizException;
import com.agent.data.skillhub.SkillRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 技能 Manifest 校验器（W14）：
 * 校验 name/version/category/prompts/tools/kb/orchestration/permissions/testcases 结构合法性。
 */
@Component
public class SkillManifestValidator {

    private static final Logger log = LoggerFactory.getLogger(SkillManifestValidator.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Set<String> VALID_CATEGORIES = Set.of("business", "data", "comm", "productivity", "utility");
    private static final Set<String> VALID_ORCHESTRATION = Set.of("none", "agent", "multi-agent", "pipeline");
    private static final Set<String> VALID_PERMISSIONS = Set.of("READ", "WRITE", "PAYMENT");

    /** 校验 manifest JSON，抛异常说明具体错误位置 */
    public void validate(String manifestJson) {
        JsonNode root;
        try {
            root = JSON.readTree(manifestJson);
        } catch (Exception e) {
            throw new BizException(400, "manifest 非合法 JSON: " + e.getMessage());
        }

        // name: 必填，小写下划线
        String name = text(root, "name");
        if (name == null || name.isBlank()) {
            throw new BizException(400, "manifest.name 必填");
        }
        if (!name.matches("^[a-z_][a-z0-9_]*$")) {
            throw new BizException(400, "manifest.name 必须小写下划线（如 trip_advisor）");
        }

        // version: 必填，semver
        String version = text(root, "version");
        if (version == null || version.isBlank()) {
            throw new BizException(400, "manifest.version 必填（semver，如 1.0.0）");
        }
        if (!version.matches("^\\d+\\.\\d+\\.\\d+(-[a-zA-Z0-9.-]+)?$")) {
            throw new BizException(400, "manifest.version 格式错误，需 semver");
        }

        // displayName / description
        String displayName = text(root, "displayName");
        if (displayName == null || displayName.isBlank()) {
            throw new BizException(400, "manifest.displayName 必填");
        }
        String description = text(root, "description");
        if (description == null || description.length() < 20) {
            throw new BizException(400, "manifest.description 至少 20 字符（供模型区分技能）");
        }

        // category
        String category = text(root, "category");
        if (category == null || !VALID_CATEGORIES.contains(category)) {
            throw new BizException(400, "manifest.category 必须是: " + VALID_CATEGORIES);
        }

        // orchestration
        String orchestration = text(root, "orchestration");
        if (orchestration == null || !VALID_ORCHESTRATION.contains(orchestration)) {
            throw new BizException(400, "manifest.orchestration 必须是: " + VALID_ORCHESTRATION);
        }

        // permissions 数组
        JsonNode perms = root.get("permissions");
        if (perms != null && perms.isArray()) {
            for (JsonNode p : perms) {
                if (!VALID_PERMISSIONS.contains(p.asText())) {
                    throw new BizException(400, "manifest.permissions 只允许: " + VALID_PERMISSIONS);
                }
            }
        }

        // tools 数组（引用工具市场工具名）
        JsonNode tools = root.get("tools");
        if (tools != null && tools.isArray()) {
            for (JsonNode t : tools) {
                String tn = t.asText();
                if (tn == null || tn.isBlank() || !tn.matches("^[a-z_][a-z0-9_]*$")) {
                    throw new BizException(400, "manifest.tools[] 必须是合法工具名（小写下划线）");
                }
            }
        }

        // kb 数组
        JsonNode kb = root.get("kb");
        if (kb != null && kb.isArray()) {
            for (JsonNode k : kb) {
                if (k.asText() == null || k.asText().isBlank()) {
                    throw new BizException(400, "manifest.kb[] 不能为空");
                }
            }
        }

        // prompts 对象
        JsonNode prompts = root.get("prompts");
        if (prompts != null && prompts.isObject()) {
            // system/router 可选
        }

        // testcases 数组（发布门禁：至少 1 条）
        JsonNode testcases = root.get("testcases");
        if (testcases == null || !testcases.isArray() || testcases.size() == 0) {
            throw new BizException(400, "manifest.testcases 至少 1 条（发布门禁）");
        }
        for (JsonNode tc : testcases) {
            if (tc.get("name") == null || tc.get("input") == null) {
                throw new BizException(400, "testcase 必须包含 name 和 input");
            }
        }

        log.debug("技能 manifest 校验通过: {}", name);
    }

    private String text(JsonNode root, String field) {
        JsonNode n = root.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }
}