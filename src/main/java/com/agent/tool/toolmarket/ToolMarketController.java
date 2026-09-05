package com.agent.tool.toolmarket;

import com.agent.common.PageResult;
import com.agent.common.Result;
import com.agent.data.toolmarket.ToolCatalogRepository;
import com.agent.data.toolmarket.ToolCatalogRepository.CatalogRow;
import com.agent.tool.ToolRegistry;
import com.agent.tool.ToolMeta;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * L5 工具市场：管理 API（docs/design/architecture/20260905-tool-marketplace.md §7.1，统一 Result<T>）。
 * 目录列表/详情、自助注册、试调、发布、下架、新版本。
 */
@RestController
@RequestMapping("/api/tool-market")
public class ToolMarketController {

    private final ToolMarketService service;
    private final ToolCatalogRepository repo;
    private final ToolRegistry toolRegistry;

    public ToolMarketController(ToolMarketService service, ToolCatalogRepository repo, ToolRegistry toolRegistry) {
        this.service = service;
        this.repo = repo;
        this.toolRegistry = toolRegistry;
    }

    /** 目录列表（分页 + category/status/搜索过滤） */
    @GetMapping
    public Result<PageResult<CatalogRow>> list(@RequestParam(defaultValue = "1") int page,
                                               @RequestParam(defaultValue = "20") int size,
                                               @RequestParam(required = false) String category,
                                               @RequestParam(required = false) String status,
                                               @RequestParam(required = false) String keyword) {
        int p = Math.max(1, page);
        int s = Math.min(100, Math.max(1, size));
        long total = repo.count(category, status, keyword);
        List<CatalogRow> items = repo.list(category, status, keyword, s, (p - 1) * s);
        return Result.ok(new PageResult<>(p, s, total, items));
    }

    /** 工具详情：当前版本 + 历史版本列表 */
    @GetMapping("/{toolName}")
    public Result<Map<String, Object>> detail(@PathVariable String toolName) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("current", service.findByCurrent(toolName).orElse(null));
        m.put("versions", service.listVersions(toolName));
        return Result.ok(m);
    }

    /** 自助注册 → DRAFT */
    @PostMapping("/register")
    public Result<CatalogRow> register(@RequestBody ToolMarketService.ToolRegRequest req,
                                       @RequestHeader(value = "X-User-Id", required = false) String userId) {
        return Result.ok(service.register(req, userId));
    }

    /** DRAFT 试调（不落库） */
    @PostMapping("/{id}/preview")
    public Result<Map<String, Object>> preview(@PathVariable long id, @RequestBody(required = false) Map<String, Object> args) {
        return Result.ok(service.preview(id, args));
    }

    /** 发布（测试用例 smoke 全绿 → PUBLISHED → 进 ToolEngine） */
    @PostMapping("/{id}/publish")
    public Result<CatalogRow> publish(@PathVariable long id,
                                      @RequestParam(required = false) String grantTenant) {
        return Result.ok(service.publish(id, grantTenant));
    }

    /** 下架（→ OFF_SHELF，出 ToolEngine） */
    @PostMapping("/{id}/off-shelf")
    public Result<Void> offShelf(@PathVariable long id) {
        service.offShelf(id);
        return Result.ok(null);
    }

    /** 发新版本（version+1 新 DRAFT，需再次 publish 才上线） */
    @PostMapping("/{id}/new-version")
    public Result<CatalogRow> newVersion(@PathVariable long id, @RequestBody ToolMarketService.ToolRegRequest req,
                                         @RequestHeader(value = "X-User-Id", required = false) String userId) {
        return Result.ok(service.newVersion(id, req, userId));
    }

    /** 执行中工具掩码（ToolRegistry 当前可执行集合，管理台对照用） */
    @GetMapping("/runtime")
    public Result<List<ToolMeta>> runtime() {
        return Result.ok(toolRegistry.listTools());
    }
}