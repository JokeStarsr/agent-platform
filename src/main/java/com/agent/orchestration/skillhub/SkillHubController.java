package com.agent.orchestration.skillhub;

import com.agent.common.PageResult;
import com.agent.common.Result;
import com.agent.data.skillhub.SkillRepository;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * L3 编排层：Skill Hub 管理 API（docs/design/architecture/20260905-skill-hub.md §7.2，统一 Result<T>）。
 */
@RestController
@RequestMapping("/api/skill-hub")
public class SkillHubController {

    private final SkillHubService service;

    public SkillHubController(SkillHubService service) {
        this.service = service;
    }

    /** 安装技能（body: manifest JSON 字符串） */
    @PostMapping("/install")
    public Result<Map<String, Object>> install(@RequestBody Map<String, Object> body,
                                               @RequestHeader("X-Tenant-Id") String tenantId) {
        String manifest = (String) body.get("manifest");
        String ownerId = (String) body.getOrDefault("ownerId", tenantId);
        var row = service.install(manifest, ownerId);
        return Result.ok(Map.of("id", row.id(), "name", row.name(), "status", row.status()));
    }

    /** 发布技能（跑测试用例全绿才通过） */
    @PostMapping("/{id}/publish")
    public Result<Map<String, Object>> publish(@PathVariable long id,
                                               @RequestHeader("X-Tenant-Id") String tenantId) {
        var row = service.publish(id);
        return Result.ok(Map.of("id", row.id(), "name", row.name(), "status", row.status()));
    }

    /** 卸载技能 */
    @PostMapping("/{id}/uninstall")
    public Result<Void> uninstall(@PathVariable long id,
                                  @RequestHeader("X-Tenant-Id") String tenantId) {
        service.uninstall(id);
        return Result.ok(null);
    }

    /** 发新版本 */
    @PostMapping("/{id}/new-version")
    public Result<Map<String, Object>> newVersion(@PathVariable long id,
                                                  @RequestBody Map<String, Object> body,
                                                  @RequestHeader("X-Tenant-Id") String tenantId) {
        String manifest = (String) body.get("manifest");
        String ownerId = (String) body.getOrDefault("ownerId", tenantId);
        var row = service.newVersion(id, manifest, ownerId);
        return Result.ok(Map.of("id", row.id(), "name", row.name(), "version", row.currentVersion(), "status", row.status()));
    }

    /** 列表（分页+过滤） */
    @GetMapping
    public Result<PageResult<Map<String, Object>>> list(@RequestParam(defaultValue = "1") int page,
                                                        @RequestParam(defaultValue = "20") int size,
                                                        @RequestParam(required = false) String status,
                                                        @RequestParam(required = false) String category,
                                                        @RequestHeader("X-Tenant-Id") String tenantId) {
        List<SkillRepository.SkillRow> rows = service.list(page, size, status, category);
        long total = service.count(status, category);
        List<Map<String, Object>> items = rows.stream().map(r -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", r.id());
            m.put("name", r.name());
            m.put("displayName", r.displayName());
            m.put("category", r.category());
            m.put("currentVersion", r.currentVersion());
            m.put("status", r.status());
            m.put("enabled", r.enabled());
            return m;
        }).toList();
        return Result.ok(new PageResult<>(page, size, total, items));
    }

    /** 详情 + 版本历史 */
    @GetMapping("/{id}")
    public Result<Map<String, Object>> detail(@PathVariable long id,
                                              @RequestHeader("X-Tenant-Id") String tenantId) {
        Map<String, Object> detail = (Map<String, Object>) service.detail(id);
        return Result.ok(detail);
    }

    /** 可用技能清单（供 GeneralAssistant 路由） */
    @GetMapping("/available")
    public Result<List<Map<String, Object>>> available(@RequestHeader("X-Tenant-Id") String tenantId) {
        return Result.ok(service.listAvailable());
    }
}