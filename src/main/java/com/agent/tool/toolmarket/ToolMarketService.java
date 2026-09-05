package com.agent.tool.toolmarket;

import com.agent.common.BizException;
import com.agent.data.toolgrant.ToolGrantRepository;
import com.agent.data.toolmarket.ToolCatalogRepository;
import com.agent.data.toolmarket.ToolCatalogRepository.CatalogRow;
import com.agent.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * L5 工具市场：注册/发布/下架/版本服务（docs/design/architecture/20260905-tool-marketplace.md §4.2）。
 * <p>生命周期：register(DRAFT) → preview(试调) → publish(测试用例 smoke 全绿 → PUBLISHED + 进 ToolEngine)
 * → offShelf(OFF_SHELF + 出 ToolEngine) / newVersion(version+1 新 DRAFT)。</p>
 */
@Service
public class ToolMarketService {

    private static final Logger log = LoggerFactory.getLogger(ToolMarketService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ToolCatalogRepository repo;
    private final ToolRegistry toolRegistry;
    private final ToolGrantRepository grantRepo;

    public ToolMarketService(ToolCatalogRepository repo, ToolRegistry toolRegistry,
                             ToolGrantRepository grantRepo) {
        this.repo = repo;
        this.toolRegistry = toolRegistry;
        this.grantRepo = grantRepo;
    }

    public record ToolRegRequest(String toolName, String displayName, String description,
                                 String category, String parameters, String permission,
                                 String behavior, List<Map<String, Object>> testcases) {
    }

    /** 自助注册 → DRAFT 行 */
    public CatalogRow register(ToolRegRequest req, String ownerId) {
        ToolRegistrationValidator.validate(req);
        if (repo.existsByName(req.toolName())) {
            throw new BizException(409, "工具已存在目录：同类名请用 new-version 发新版");
        }
        String tc = toJson(req.testcases() == null ? List.of() : req.testcases());
        String params = req.parameters() == null || req.parameters().isBlank()
                ? "{\"type\":\"object\",\"properties\":{}}" : req.parameters();
        return repo.insert(new CatalogRow(
                0, req.toolName(), 0, req.displayName(), req.description().trim(),
                req.category() == null ? "utility" : req.category(),
                params, req.permission(), "SELF_REGISTERED", null, null,
                "DRAFT", true, ownerId == null ? "platform" : ownerId, tc, null, null));
    }

    /** DRAFT/已发布工具试调（不落调用记录、不注册执行） */
    public Map<String, Object> preview(long id, Map<String, Object> args) {
        CatalogRow r = requireRow(id);
        return toDynamicTool(r).execute(args == null ? Map.of() : args);
    }

    /** 发布：测试用例 smoke 全绿 → PUBLISHED + 注册进 ToolEngine + 自动授权调用租户 */
    public CatalogRow publish(long id, String grantTenant) {
        CatalogRow r = requireRow(id);
        if (!"DRAFT".equals(r.status())) {
            throw new BizException(409, "仅 DRAFT 可发布，当前状态: " + r.status());
        }
        if ("PAYMENT".equals(r.permission())) {
            throw new BizException(400, "PAYMENT 工具不可发布：本期仅登记不放开");
        }
        runSmokeTests(r);
        repo.updateStatus(id, "PUBLISHED");
        toolRegistry.register(toDynamicTool(r));
        // 自动授权到调用租户（演示"注册→发布→可调用"闭环；授权仍可随时在 MCP 授权表回收）
        String tenant = grantTenant == null || grantTenant.isBlank() ? "default" : grantTenant;
        grantRepo.setEnabled(tenant, r.toolName(), true);
        log.info("工具发布成功: {} v{} (source={})", r.toolName(), r.version(), r.source());
        return findByCurrent(r.toolName()).orElse(r);
    }

    /** 下架：OFF_SHELF + 注销执行注册 */
    public void offShelf(long id) {
        CatalogRow r = requireRow(id);
        if ("PUBLISHED".equals(r.status())) {
            toolRegistry.unregister(r.toolName());
        }
        repo.updateStatus(id, "OFF_SHELF");
        log.info("工具下架: {} v{}", r.toolName(), r.version());
    }

    /** 发新版本：version+1 的新 DRAFT（复用注册校验，不自动发布） */
    public CatalogRow newVersion(long id, ToolRegRequest req, String ownerId) {
        requireRow(id);
        ToolRegistrationValidator.validate(req);
        String tc = toJson(req.testcases() == null ? List.of() : req.testcases());
        return repo.insert(new CatalogRow(
                0, req.toolName(), 0, req.displayName(), req.description().trim(),
                req.category() == null ? "utility" : req.category(),
                req.parameters() == null ? "{\"type\":\"object\",\"properties\":{}}" : req.parameters(),
                req.permission(), "SELF_REGISTERED", null, null,
                "DRAFT", true, ownerId == null ? "platform" : ownerId, tc, null, null));
    }

    public Optional<CatalogRow> findByCurrent(String toolName) {
        return repo.findByCurrent(toolName);
    }

    public List<CatalogRow> listVersions(String toolName) {
        return repo.listVersions(toolName);
    }

    /* ---------- 内部 ---------- */

    private CatalogRow requireRow(long id) {
        return repo.findById(id)
                .orElseThrow(() -> new BizException(404, "工具不存在: id=" + id));
    }

    private void runSmokeTests(CatalogRow r) {
        List<Map<String, Object>> testcases = parseTestcases(r.testcaseJson());
        DynamicAgentTool tool = toDynamicTool(r);
        for (Map<String, Object> tc : testcases) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> args = (Map<String, Object>) tc.get("arguments");
                tool.execute(args == null ? Map.of() : args);
            } catch (Exception e) {
                throw new BizException(400, "测试用例 smoke 失败 [" + tc.get("name") + "]: " + e.getMessage());
            }
        }
    }

    private DynamicAgentTool toDynamicTool(CatalogRow r) {
        return new DynamicAgentTool(r.toolName(), r.description(), r.parameters(), r.permission(),
                30_000, "MOCK", parseTestcases(r.testcaseJson()));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseTestcases(String tc) {
        if (tc == null || tc.isBlank()) {
            return List.of();
        }
        try {
            return JSON.readValue(tc, List.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    private String toJson(Object v) {
        try {
            return JSON.writeValueAsString(v);
        } catch (Exception e) {
            return "[]";
        }
    }
}