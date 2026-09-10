package com.agent.app.openplatform;

import com.agent.data.openplatform.ApiKeyRepository;
import com.agent.data.openplatform.ApiKeyRepository.ApiKey;
import com.agent.data.openplatform.TenantQuotaRepository;
import com.agent.data.openplatform.TenantQuotaRepository.TenantQuota;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * L1 接入层：开放平台服务（W17）
 * <p>管理 API Key（创建/列表/禁用/删除）和租户配额（QPS/Token/预算）。</p>
 */
@Service
public class OpenPlatformService {

    private static final Logger log = LoggerFactory.getLogger(OpenPlatformService.class);

    private final ApiKeyRepository apiKeyRepo;
    private final TenantQuotaRepository quotaRepo;

    public OpenPlatformService(ApiKeyRepository apiKeyRepo, TenantQuotaRepository quotaRepo) {
        this.apiKeyRepo = apiKeyRepo;
        this.quotaRepo = quotaRepo;
    }

    /**
     * 创建 API Key（返回明文 Key，仅展示一次）。
     */
    public Map<String, Object> createApiKey(String tenantId, String name, Instant expiresAt) {
        // 生成 API Key：sk- + 32 位随机字符
        String apiKey = "sk-" + UUID.randomUUID().toString().replace("-", "");
        String apiKeyPrefix = apiKey.substring(0, 10);  // sk-abc1234
        String apiKeyHash = DigestUtils.sha256Hex(apiKey);

        long id = apiKeyRepo.insert(tenantId, apiKeyPrefix, apiKeyHash, name, expiresAt);
        log.info("创建 API Key: tenant={}, name={}, prefix={}", tenantId, name, apiKeyPrefix);

        return Map.of(
                "id", id,
                "apiKey", apiKey,  // 仅创建时返回明文
                "apiKeyPrefix", apiKeyPrefix,
                "name", name,
                "expiresAt", expiresAt != null ? expiresAt.toString() : null
        );
    }

    /**
     * 查询租户 API Key 列表（仅返回前缀，不返回明文）。
     */
    public List<Map<String, Object>> listApiKeys(String tenantId) {
        List<ApiKey> keys = apiKeyRepo.findByTenantId(tenantId);
        return keys.stream().map(k -> Map.<String, Object>of(
                "id", k.id(),
                "apiKeyPrefix", k.apiKeyPrefix() + "...",
                "name", k.name(),
                "status", k.status(),
                "expiresAt", k.expiresAt() != null ? k.expiresAt().toString() : null,
                "createdAt", k.createdAt().toString()
        )).toList();
    }

    /**
     * 禁用 API Key。
     */
    public boolean disableApiKey(long id, String tenantId) {
        Optional<ApiKey> keyOpt = apiKeyRepo.findById(id);
        if (keyOpt.isEmpty() || !keyOpt.get().tenantId().equals(tenantId)) {
            return false;
        }
        apiKeyRepo.updateStatus(id, "disabled");
        log.info("禁用 API Key: id={}, tenant={}", id, tenantId);
        return true;
    }

    /**
     * 删除 API Key。
     */
    public boolean deleteApiKey(long id, String tenantId) {
        Optional<ApiKey> keyOpt = apiKeyRepo.findById(id);
        if (keyOpt.isEmpty() || !keyOpt.get().tenantId().equals(tenantId)) {
            return false;
        }
        apiKeyRepo.deleteById(id);
        log.info("删除 API Key: id={}, tenant={}", id, tenantId);
        return true;
    }

    /**
     * 设置租户配额。
     */
    public void setTenantQuota(String tenantId, int qps, long dailyTokenQuota, java.math.BigDecimal dailyBudget) {
        quotaRepo.upsert(tenantId, qps, dailyTokenQuota, dailyBudget);
        log.info("设置租户配额: tenant={}, qps={}, token={}, budget={}", tenantId, qps, dailyTokenQuota, dailyBudget);
    }

    /**
     * 查询租户配额。
     */
    public Optional<TenantQuota> getTenantQuota(String tenantId) {
        return quotaRepo.findByTenantId(tenantId);
    }

    /**
     * 清除租户预算超支标记（管理员操作）。
     */
    public boolean clearBudgetExceeded(String tenantId) {
        int updated = quotaRepo.clearBudgetExceeded(tenantId);
        if (updated > 0) {
            log.info("清除租户预算超支标记: tenant={}", tenantId);
            return true;
        }
        return false;
    }
}
