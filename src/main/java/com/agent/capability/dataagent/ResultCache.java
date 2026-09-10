package com.agent.capability.dataagent;

import com.agent.capability.dataagent.DataAgentService.QueryResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * L4 能力层：推理结果缓存（W18）
 * <p>缓存 NL2SQL 查询结果：相同问题 + 参数命中缓存，避免重复调 LLM 生成 SQL 和重复查库。
 * 高频同问命中率 ≥ 30% 是 W18 周末检查点。</p>
 *
 * <p>缓存键：SHA-256(tenantId ? type=today ? question ? maxRows)</p>
 * <p>TTL：默认 5 分钟（业务数据变更后 5 分钟内自动过期）</p>
 * <p>打点：命中数/未命中数/命中率统计（供看板展示）</p>
 */
@Component
public class ResultCache {

    private static final Logger log = LoggerFactory.getLogger(ResultCache.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();

    @Value("${app.result-cache.ttl-seconds:300}")
    private long ttlSeconds;

    @Value("${app.result-cache.max-entries:1000}")
    private int maxEntries;

    @PostConstruct
    void init() {
        log.info("结果缓存初始化: ttl={}s, maxEntries={}", ttlSeconds, maxEntries);
    }

    /**
     * 缓存条目。
     */
    private record CacheEntry(String key, QueryResult result, Instant expireAt, Instant createdAt) {
    }

    /**
     * 根据查询参数生成缓存键。
     */
    public String buildKey(String tenantId, String question, Integer maxRows) {
        String raw = tenantId + "|" + question + "|" + (maxRows != null ? maxRows : 100);
        return hash(raw);
    }

    /**
     * 生成"含复算校验"完整结果的缓存键（与基础键区分，避免校验被跳过）。
     */
    public String buildVerifiedKey(String tenantId, String question, Integer maxRows) {
        String raw = tenantId + "|v|" + question + "|" + (maxRows != null ? maxRows : 100);
        return hash(raw);
    }

    private String hash(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 12; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(raw.hashCode());
        }
    }

    /**
     * 从缓存获取查询结果（命中返回，未命中返回 null）。
     */
    public QueryResult get(String tenantId, String question, Integer maxRows) {
        String key = buildKey(tenantId, question, maxRows);
        CacheEntry entry = cache.get(key);
        if (entry == null) {
            misses.increment();
            return null;
        }

        // 检查 TTL 过期
        if (entry.expireAt().isBefore(Instant.now())) {
            cache.remove(key);
            misses.increment();
            log.debug("缓存过期: key={}", key);
            return null;
        }

        hits.increment();
        log.debug("缓存命中: key={}, age={}s", key,
                java.time.Duration.between(entry.createdAt(), Instant.now()).getSeconds());
        return entry.result();
    }

    /**
     * 写入缓存（LRU 容量控制）。
     */
    public void put(String tenantId, String question, Integer maxRows, QueryResult result) {
        putByKey(buildKey(tenantId, question, maxRows), result);
    }

    /**
     * 写入缓存（使用指定键，用于"含复算校验"完整结果）。
     */
    public void putVerified(String tenantId, String question, Integer maxRows, QueryResult result) {
        putByKey(buildVerifiedKey(tenantId, question, maxRows), result);
    }

    private void putByKey(String key, QueryResult result) {
        // 容量控制：超过上限时清理过期条目，仍超则清最旧的
        if (cache.size() >= maxEntries) {
            cache.entrySet().removeIf(e -> e.getValue().expireAt().isBefore(Instant.now()));
            if (cache.size() >= maxEntries) {
                CacheEntry oldest = cache.values().stream()
                        .min((a, b) -> a.createdAt().compareTo(b.createdAt()))
                        .orElse(null);
                if (oldest != null) {
                    cache.remove(oldest.key());
                }
            }
        }

        cache.put(key, new CacheEntry(key, result,
                Instant.now().plusSeconds(ttlSeconds), Instant.now()));
        log.debug("缓存写入: key={}", key);
    }

    /**
     * 从缓存获取"含复算校验"完整结果。
     */
    public QueryResult getVerified(String tenantId, String question, Integer maxRows) {
        String key = buildVerifiedKey(tenantId, question, maxRows);
        CacheEntry entry = cache.get(key);
        if (entry == null) {
            misses.increment();
            return null;
        }
        if (entry.expireAt().isBefore(Instant.now())) {
            cache.remove(key);
            misses.increment();
            return null;
        }
        hits.increment();
        return entry.result();
    }

    /**
     * 清除缓存（Schema 变更/数据刷新时调用）。
     */
    public void clear() {
        cache.clear();
        log.info("结果缓存已清空");
    }

    /**
     * 清除指定租户的缓存。
     */
    public void clearTenant(String tenantId) {
        cache.entrySet().removeIf(e -> e.getValue().result().question() != null
                && e.getKey().startsWith(shaPrefix(tenantId)));
        log.info("租户缓存已清空: tenant={}", tenantId);
    }

    /**
     * 缓存统计。
     */
    public Map<String, Object> stats() {
        long hit = hits.sum();
        long miss = misses.sum();
        double hitRate = (hit + miss) > 0 ? (double) hit / (hit + miss) * 100 : 0;

        return Map.of(
                "hits", hit,
                "misses", miss,
                "hitRate", Math.round(hitRate * 10) / 10.0,
                "cacheSize", cache.size(),
                "maxEntries", maxEntries,
                "ttlSeconds", ttlSeconds
        );
    }

    /**
     * 计算租户前缀哈希（用于按租户清缓存）。
     */
    private String shaPrefix(String tenantId) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest((tenantId + "|").getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 12; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return tenantId;
        }
    }
}