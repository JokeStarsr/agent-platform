package com.agent.orchestration.skillhub;

import com.agent.common.BizException;
import com.agent.data.skillhub.SkillRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * L3 编排层：Skill Hub 服务（docs/design/architecture/20260905-skill-hub.md §4-5，W14）。
 * <p>技能生命周期：install(DRAFT) → 测试用例全绿 → PUBLISHED（进目录启用）→ 卸载(OFF_SHELF) / 新版本(version+1 DRAFT)。
 * 发布门禁：manifest.testcases 全绿才能 PUBLISHED。</p>
 */
@Service
public class SkillHubService {

    private static final Logger log = LoggerFactory.getLogger(SkillHubService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final SkillRepository repo;
    private final SkillManifestValidator validator;
    private final SkillExecutor executor; // 复用 AgentRuntime/MultiAgentService/ToolEngine

    public SkillHubService(SkillRepository repo, SkillManifestValidator validator, SkillExecutor executor) {
        this.repo = repo;
        this.validator = validator;
        this.executor = executor;
    }

    /** 安装技能：校验 manifest → 写 t_skill(DRAFT) + t_skill_version(DRAFT) */
    public SkillRepository.SkillRow install(String manifestJson, String ownerId) {
        validator.validate(manifestJson);
        JsonNode root = parseJson(manifestJson);
        String name = root.get("name").asText();

        if (repo.findByName(name).isPresent()) {
            throw new BizException(409, "技能已存在：" + name + "，请用 new-version 发新版");
        }

        // 插入 t_skill
        SkillRepository.SkillRow row = repo.insert(new SkillRepository.SkillRow(
                0, name,
                root.get("displayName").asText(),
                root.get("description").asText(),
                root.get("category").asText(),
                root.get("version").asText(),
                "DRAFT", true,
                ownerId == null ? "platform" : ownerId,
                null, null));

        // 插入 t_skill_version
        repo.insertVersion(new SkillRepository.VersionRow(
                0, row.id(), root.get("version").asText(), manifestJson, "DRAFT", null, null, null));

        log.info("技能安装 DRAFT: {} v{}", name, root.get("version").asText());
        return row;
    }

    /** 发布技能：跑测试用例 → 全绿 → PUBLISHED + enabled=true */
    public SkillRepository.SkillRow publish(long skillId) {
        SkillRepository.SkillRow row = repo.findById(skillId)
                .orElseThrow(() -> new BizException(404, "技能不存在: id=" + skillId));

        if (!"DRAFT".equals(row.status())) {
            throw new BizException(409, "仅 DRAFT 可发布，当前状态: " + row.status());
        }

        // 取最新版本 manifest
        JsonNode root = parseJson(repo.listVersions(row.id()).get(0).manifestJson());

        // 跑测试用例
        Map<String, Object> testResult = runTestcases(root.get("testcases"), row);
        int total = (int) testResult.get("total");
        int passed = (int) testResult.get("passed");

        if (passed < total) {
            String report;
            try {
                report = JSON.writeValueAsString(testResult.get("details"));
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                report = "{\"error\":\"serialization failed\"}";
            }
            repo.updateVersionStatus(repo.listVersions(row.id()).get(0).id(), "FAILED", report, null);
            throw new BizException(400, "测试用例未全绿: " + passed + "/" + total + "，发布被拦截");
        }

        // 全绿 → PUBLISHED
        String report;
        try {
            report = JSON.writeValueAsString(testResult.get("details"));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            report = "{\"error\":\"serialization failed\"}";
        }
        repo.updateVersionStatus(repo.listVersions(row.id()).get(0).id(), "PUBLISHED", report, OffsetDateTime.now());
        repo.updateVersionAndStatus(row.id(), root.get("version").asText(), "PUBLISHED");
        repo.setEnabled(row.id(), true);

        log.info("技能发布成功: {} v{} ({}/{})", row.name(), row.currentVersion(), passed, total);
        return repo.findById(row.id()).orElse(row);
    }

    /** 卸载：OFF_SHELF + enabled=false */
    public void uninstall(long skillId) {
        SkillRepository.SkillRow row = repo.findById(skillId)
                .orElseThrow(() -> new BizException(404, "技能不存在: id=" + skillId));
        repo.updateStatus(skillId, "OFF_SHELF");
        repo.setEnabled(skillId, false);
        log.info("技能卸载: {}", row.name());
    }

    /** 发新版本：version+1 → 新 DRAFT（复用校验，不自动发布） */
    public SkillRepository.SkillRow newVersion(long skillId, String manifestJson, String ownerId) {
        SkillRepository.SkillRow row = repo.findById(skillId)
                .orElseThrow(() -> new BizException(404, "技能不存在: id=" + skillId));
        validator.validate(manifestJson);
        JsonNode root = parseJson(manifestJson);

        if (!root.get("name").asText().equals(row.name())) {
            throw new BizException(400, "新版本 name 必须与原技能一致");
        }

        // 插入新版本记录
        repo.insertVersion(new SkillRepository.VersionRow(
                0, row.id(), root.get("version").asText(), manifestJson, "DRAFT", null, null, null));

        // 更新 t_skill 当前版本号（状态保持 DRAFT）
        repo.updateVersionAndStatus(row.id(), root.get("version").asText(), "DRAFT");

        log.info("技能新版本 DRAFT: {} v{}", row.name(), root.get("version").asText());
        return repo.findById(row.id()).orElse(row);
    }

    /** 列表（分页+过滤） */
    public List<SkillRepository.SkillRow> list(int page, int size, String status, String category) {
        int limit = Math.min(50, Math.max(1, size));
        int offset = (Math.max(1, page) - 1) * limit;
        return repo.list(limit, offset, status, category);
    }

    /** 总数 */
    public long count(String status, String category) {
        return repo.count(status, category);
    }

    /** 详情 + 版本历史 */
    public Map<String, Object> detail(long skillId) {
        SkillRepository.SkillRow row = repo.findById(skillId)
                .orElseThrow(() -> new BizException(404, "技能不存在: id=" + skillId));
        List<SkillRepository.VersionRow> versions = repo.listVersions(row.id());

        var skillMap = new java.util.LinkedHashMap<String, Object>();
        skillMap.put("id", row.id());
        skillMap.put("name", row.name());
        skillMap.put("displayName", row.displayName());
        skillMap.put("description", row.description());
        skillMap.put("category", row.category());
        skillMap.put("currentVersion", row.currentVersion());
        skillMap.put("status", row.status());
        skillMap.put("enabled", row.enabled());
        skillMap.put("ownerId", row.ownerId());
        skillMap.put("createdAt", row.createdAt());
        skillMap.put("updatedAt", row.updatedAt());

        var versionList = versions.stream().map(v -> {
            var m = new java.util.LinkedHashMap<String, Object>();
            m.put("id", v.id());
            m.put("version", v.version());
            m.put("status", v.status());
            m.put("testResult", v.testResult());
            m.put("releasedAt", v.releasedAt());
            m.put("createdAt", v.createdAt());
            return m;
        }).toList();

        var result = new java.util.LinkedHashMap<String, Object>();
        result.put("skill", skillMap);
        result.put("versions", versionList);
        return result;
    }

    /** 按名称取技能当前行（GeneralAssistant 执行用，保留真实 manifest） */
    public Optional<SkillRepository.SkillRow> findByName(String name) {
        return repo.findByName(name);
    }

    /** 获取可用技能清单（PUBLISHED + enabled）供 GeneralAssistant 路由用 */
    public List<Map<String, Object>> listAvailable() {
        return repo.list(100, 0, "PUBLISHED", null).stream()
                .filter(SkillRepository.SkillRow::enabled)
                .map(r -> {
                    JsonNode manifest = parseJson(repo.listVersions(r.id()).get(0).manifestJson());
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("name", r.name());
                    m.put("displayName", r.displayName());
                    m.put("description", r.description());
                    m.put("category", r.category());
                    m.put("orchestration", manifest.get("orchestration").asText());
                    List<String> perms = new java.util.ArrayList<>();
                    JsonNode permsNode = manifest.get("permissions");
                    if (permsNode != null && permsNode.isArray()) {
                        permsNode.forEach(p -> perms.add(p.asText()));
                    }
                    m.put("permissions", perms);
                    return m;
                })
                .toList();
    }

    /* ---------- 内部：测试用例执行 ---------- */

    private Map<String, Object> runTestcases(JsonNode testcases, SkillRepository.SkillRow skill) {
        int total = testcases.size();
        int passed = 0;
        var details = new java.util.ArrayList<Map<String, Object>>();

        for (JsonNode tc : testcases) {
            String name = tc.get("name").asText();
            String input = tc.get("input").asText();
            try {
                // 通过 SkillExecutor 执行（复用 AgentRuntime/MultiAgentService）
                executor.execute(skill, input);
                passed++;
                details.add(Map.of("name", name, "passed", true));
            } catch (Exception e) {
                details.add(Map.of("name", name, "passed", false, "error", e.getMessage()));
            }
        }
        return Map.of("total", total, "passed", passed, "details", details);
    }

    private JsonNode parseJson(String s) {
        try {
            return JSON.readTree(s);
        } catch (Exception e) {
            throw new BizException(400, "JSON 解析失败: " + e.getMessage());
        }
    }
}