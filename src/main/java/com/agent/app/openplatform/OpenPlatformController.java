package com.agent.app.openplatform;

import com.agent.common.Result;
import com.agent.data.openplatform.TenantQuotaRepository.TenantQuota;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * L1 接入层：开放平台管理 API（W17）
 * <p>提供 API Key 管理（创建/列表/禁用/删除）和租户配额管理（设置/查询）。</p>
 */
@RestController
@RequestMapping("/api/open")
public class OpenPlatformController {

    private final OpenPlatformService openPlatformService;

    public OpenPlatformController(OpenPlatformService openPlatformService) {
        this.openPlatformService = openPlatformService;
    }

    // ========== API Key 管理 ==========

    /**
     * 创建 API Key（返回明文 Key，仅展示一次）。
     */
    @PostMapping("/keys")
    public Result<Map<String, Object>> createApiKey(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestBody CreateApiKeyRequest req) {
        Instant expiresAt = req.expiresAt() != null ? Instant.parse(req.expiresAt()) : null;
        Map<String, Object> result = openPlatformService.createApiKey(tenantId, req.name(), expiresAt);
        return Result.ok(result);
    }

    /**
     * 查询租户 API Key 列表（仅返回前缀，不返回明文）。
     */
    @GetMapping("/keys")
    public Result<List<Map<String, Object>>> listApiKeys(@RequestHeader("X-Tenant-Id") String tenantId) {
        List<Map<String, Object>> keys = openPlatformService.listApiKeys(tenantId);
        return Result.ok(keys);
    }

    /**
     * 禁用 API Key。
     */
    @PutMapping("/keys/{id}/disable")
    public Result<Void> disableApiKey(
            @PathVariable long id,
            @RequestHeader("X-Tenant-Id") String tenantId) {
        boolean success = openPlatformService.disableApiKey(id, tenantId);
        if (!success) {
            return Result.error(404, "API Key 不存在或无权操作");
        }
        return Result.ok(null);
    }

    /**
     * 删除 API Key。
     */
    @DeleteMapping("/keys/{id}")
    public Result<Void> deleteApiKey(
            @PathVariable long id,
            @RequestHeader("X-Tenant-Id") String tenantId) {
        boolean success = openPlatformService.deleteApiKey(id, tenantId);
        if (!success) {
            return Result.error(404, "API Key 不存在或无权操作");
        }
        return Result.ok(null);
    }

    // ========== 租户配额管理 ==========

    /**
     * 设置租户配额（管理员操作）。
     */
    @PutMapping("/quota")
    public Result<Void> setTenantQuota(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestBody SetQuotaRequest req) {
        openPlatformService.setTenantQuota(tenantId, req.qps(), req.dailyTokenQuota(), req.dailyBudget());
        return Result.ok(null);
    }

    /**
     * 查询租户配额。
     */
    @GetMapping("/quota")
    public Result<Map<String, Object>> getTenantQuota(@RequestHeader("X-Tenant-Id") String tenantId) {
        Optional<TenantQuota> quotaOpt = openPlatformService.getTenantQuota(tenantId);
        if (quotaOpt.isEmpty()) {
            return Result.error(404, "租户配额未配置");
        }
        TenantQuota quota = quotaOpt.get();
        Map<String, Object> result = Map.of(
                "tenantId", quota.tenantId(),
                "qps", quota.qps(),
                "dailyTokenQuota", quota.dailyTokenQuota(),
                "dailyBudget", quota.dailyBudget(),
                "budgetExceeded", quota.budgetExceeded()
        );
        return Result.ok(result);
    }

    /**
     * 清除租户预算超支标记（管理员操作）。
     */
    @PostMapping("/quota/clear-budget-exceeded")
    public Result<Void> clearBudgetExceeded(@RequestHeader("X-Tenant-Id") String tenantId) {
        boolean success = openPlatformService.clearBudgetExceeded(tenantId);
        if (!success) {
            return Result.error(404, "租户配额未配置");
        }
        return Result.ok(null);
    }

    // ========== Request DTOs ==========

    public record CreateApiKeyRequest(String name, String expiresAt) {}

    public record SetQuotaRequest(int qps, long dailyTokenQuota, BigDecimal dailyBudget) {}
}
