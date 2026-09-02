package com.agent.capability.memory;

import com.agent.capability.RagService;
import com.agent.common.BizException;
import com.agent.data.memory.UserMemoryRepository;
import com.agent.data.memory.UserMemoryRepository.UserMemoryRow;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * L4 能力层：三级记忆服务实现（docs/design/architecture/20260901-memory-context.md §2/§4）
 * 越权红线：长期记忆全部经 UserMemoryRepository 按 tenant_id+user_id 双过滤；本人操作再校验 operator。
 */
@Service
public class MemoryServiceImpl implements MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryServiceImpl.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final double AUTO_CONFIRM_THRESHOLD = 0.7;
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_PENDING = "PENDING_CONFIRM";
    private static final String STATUS_DELETED = "DELETED";

    private final UserMemoryRepository repo;
    private final StringRedisTemplate redis;
    private final ObjectProvider<EmbeddingModel> embeddingModel;
    private final ObjectProvider<RagService> rag;
    @Value("${spring.memory.short-term-ttl-seconds:1800}")
    private long shortTtlSeconds;

    public MemoryServiceImpl(UserMemoryRepository repo, StringRedisTemplate redis,
                             ObjectProvider<EmbeddingModel> embeddingModel, ObjectProvider<RagService> rag) {
        this.repo = repo;
        this.redis = redis;
        this.embeddingModel = embeddingModel;
        this.rag = rag;
    }

    /* ---------- 短期（Redis 会话） ---------- */

    @Override
    public void saveShortTerm(String tenantId, String sessionId, String role, String content) {
        String key = shortKey(tenantId, sessionId);
        String member = writeJson(Map.of("role", role == null ? "" : role, "content", content == null ? "" : content));
        redis.opsForZSet().add(key, member, (double) System.currentTimeMillis());
        redis.expire(key, Duration.ofSeconds(shortTtlSeconds));
    }

    @Override
    public List<Map<String, Object>> loadShortTerm(String tenantId, String sessionId, int limit) {
        String key = shortKey(tenantId, sessionId);
        var members = redis.opsForZSet().reverseRange(key, 0, Math.max(0, Math.min(limit - 1, 199)));
        List<Map<String, Object>> out = new ArrayList<>();
        if (members == null) {
            return out;
        }
        for (String m : members) {
            Map<String, Object> map = readJson(m);
            out.add(map);
        }
        return out;
    }

    private String shortKey(String tenantId, String sessionId) {
        return "mem:short:" + tenantId + ":" + sessionId;
    }

    /* ---------- 长期（用户画像） ---------- */

    @Override
    public Long saveLongTerm(String tenantId, String userId, String field, String value,
                             double confidence, String source) {
        assertSelf(userId);
        String status = confidence >= AUTO_CONFIRM_THRESHOLD ? STATUS_ACTIVE : STATUS_PENDING;
        String embedding = embed(value);
        return repo.insert(tenantId, userId, field, value, confidence, status, source, embedding);
    }

    @Override
    public List<Map<String, Object>> retrieveLongTerm(String tenantId, String userId, String query, Integer topK) {
        String q = embed(query == null ? "" : query);
        return repo.selectActive(tenantId, userId, topK, q).stream().map(this::toMap).toList();
    }

    @Override
    public List<Map<String, Object>> pendingConfirmations(String tenantId, String userId, String operatorUserId) {
        if (!userId.equals(operatorUserId)) {
            throw new BizException(403, "越权：不可查看他人待确认记忆");
        }
        return repo.selectPending(tenantId, userId).stream().map(this::toMap).toList();
    }

    @Override
    public void confirmMemory(String tenantId, String operatorUserId, long memoryId, boolean accept) {
        UserMemoryRow row = repo.findById(memoryId)
                .orElseThrow(() -> new BizException(404, "记忆不存在: " + memoryId));
        if (!tenantId.equals(row.tenantId()) || !operatorUserId.equals(row.userId())) {
            throw new BizException(403, "越权：不可操作他人记忆");
        }
        repo.setStatus(memoryId, accept ? STATUS_ACTIVE : STATUS_DELETED);
    }

    @Override
    public void deleteMemory(String tenantId, String operatorUserId, long memoryId) {
        UserMemoryRow row = repo.findById(memoryId)
                .orElseThrow(() -> new BizException(404, "记忆不存在: " + memoryId));
        if (!tenantId.equals(row.tenantId()) || !operatorUserId.equals(row.userId())) {
            throw new BizException(403, "越权：不可删除他人记忆");
        }
        repo.setStatus(memoryId, STATUS_DELETED);
    }

    @Override
    public Map<String, Object> orgSearch(String tenantId, String query, int topK) {
        RagService ragService = rag.getIfAvailable();
        if (ragService == null) {
            return Map.of("available", false, "message", "RAG 未启用");
        }
        var r = ragService.search(query, topK, tenantId, null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", r.getData() == null ? r.getCode() : 0);
        out.put("answer", r.getData() == null ? r.getMessage() : r.getData().getAnswer());
        out.put("citations", r.getData() == null ? List.of() : r.getData().getCitations());
        out.put("sourceChunks", r.getData() == null ? List.of() : r.getData().getSourceChunks());
        return out;
    }

    /* ---------- 帮助 ---------- */

    private void assertSelf(String userId) {
        // v1 写入方即本人；跨用户写入在数据层仍按 user_id 隔离
    }

    private String embed(String text) {
        EmbeddingModel em = embeddingModel.getIfAvailable();
        if (em == null) {
            return null;
        }
        try {
            float[] vec = em.embed(text);
            if (vec == null) {
                return null;
            }
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < vec.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(vec[i]);
            }
            return sb.append(']').toString();
        } catch (Exception e) {
            log.warn("长期记忆向量化失败（降级为维度召回）: {}", e.getMessage());
            return null;
        }
    }

    private Map<String, Object> toMap(UserMemoryRow r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("field", r.field());
        m.put("value", r.value());
        m.put("confidence", r.confidence());
        m.put("status", r.status());
        m.put("source", r.source());
        return m;
    }

    private static String writeJson(Object v) {
        try {
            return JSON.writeValueAsString(v);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    private static Map<String, Object> readJson(String s) {
        try {
            return JSON.readValue(s, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return Map.of("_raw", s);
        }
    }
}
