package com.agent.orchestration.appfactory;

import com.agent.common.BizException;
import com.agent.common.PageResult;
import com.agent.common.Result;
import com.agent.data.application.AppRepository;
import com.agent.data.application.AppRepository.AppRow;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * L3 编排层：应用工厂 REST 接口（docs/design/api/20260902-app-factory.md §2.3）
 * 租户隔离：读取 X-Tenant-Id（缺省 default）；创建/启停时全量校验（AppValidator）。
 */
@RestController
@RequestMapping("/api/apps")
public class AppController {

    private final AppRepository repo;
    private final AppRegistry registry;
    private final AppValidator validator;

    public AppController(AppRepository repo, AppRegistry registry, AppValidator validator) {
        this.repo = repo;
        this.registry = registry;
        this.validator = validator;
    }

    /** 应用列表（分页，租户内 + GLOBAL 平台应用可见） */
    @GetMapping
    public Result<PageResult<Map<String, Object>>> list(@RequestParam(defaultValue = "1") int page,
                                                        @RequestParam(defaultValue = "20") int size,
                                                        @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        long total = repo.countVisible(tenantId);
        List<AppRow> rows = repo.pageVisible(tenantId, page, size);
        return Result.ok(PageResult.of(page, size, total, rows.stream().map(this::toMap).toList()));
    }

    /** 应用详情 */
    @GetMapping("/{appId}")
    public Result<Map<String, Object>> detail(@PathVariable String appId,
                                              @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        return Result.ok(toMap(require(tenantId, appId)));
    }

    /** 创建应用（CREATED + v1，全量校验） */
    @PostMapping
    public Result<Map<String, Object>> create(@Valid @RequestBody CreateRequest req,
                                              @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        if (repo.findByAppId(tenantId, req.appId()).isPresent()) {
            throw new BizException(409, "应用已存在: " + req.appId());
        }
        validator.validate(req.configJson());
        AppRow row = repo.create(tenantId, req.appId(), req.name(), "CREATED", req.configJson());
        registry.refresh(tenantId, req.appId());
        return Result.ok(toMap(row));
    }

    /** 启用：CREATED/SUSPENDED → ENABLED（启用前全量校验） */
    @PostMapping("/{appId}/start")
    public Result<Map<String, Object>> start(@PathVariable String appId,
                                             @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        AppRow row = require(tenantId, appId);
        if ("ENABLED".equals(row.status())) {
            throw new BizException(409, "应用已启用: " + appId);
        }
        validator.validate(row.configJson());
        repo.updateStatus(tenantId, appId, "ENABLED");
        registry.refresh(tenantId, appId);
        return Result.ok(toMap(require(tenantId, appId)));
    }

    /** 停用：ENABLED → SUSPENDED（停用后消费点拒绝提交新任务） */
    @PostMapping("/{appId}/suspend")
    public Result<Map<String, Object>> suspend(@PathVariable String appId,
                                               @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        AppRow row = require(tenantId, appId);
        if (!"ENABLED".equals(row.status())) {
            throw new BizException(409, "仅 ENABLED 可停用，当前: " + row.status());
        }
        repo.updateStatus(tenantId, appId, "SUSPENDED");
        registry.refresh(tenantId, appId);
        return Result.ok(toMap(require(tenantId, appId)));
    }

    private AppRow require(String tenantId, String appId) {
        return repo.findByAppId(tenantId, appId)
                .orElseThrow(() -> new BizException(404, "应用不存在: " + appId));
    }

    private Map<String, Object> toMap(AppRow r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("appId", r.appId());
        m.put("name", r.name());
        m.put("status", r.status());
        m.put("version", r.version());
        m.put("configJson", r.configJson());
        m.put("createdAt", r.createdAt() == null ? null : r.createdAt().toString());
        m.put("updatedAt", r.updatedAt() == null ? null : r.updatedAt().toString());
        return m;
    }

    public record CreateRequest(@NotBlank(message = "appId 不能为空") String appId,
                                @NotBlank(message = "name 不能为空") String name,
                                @NotBlank(message = "configJson 不能为空") String configJson) {
    }
}