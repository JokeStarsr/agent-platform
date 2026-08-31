package com.agent.capability.memory;

import com.agent.capability.RagService;
import com.agent.data.memory.UserMemoryRepository;
import com.agent.data.memory.UserMemoryRepository.UserMemoryRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 记忆服务单测（docs/design/architecture/20260901-memory-context.md §7 AC-1/AC-4 + 确认/遗忘流）
 */
class MemoryServiceTest {

    private static final String TENANT = "default";

    private FakeUserMemoryRepository repo;
    private StringRedisTemplate redis;
    private MemoryServiceImpl cut;

    @BeforeEach
    void setUp() {
        repo = new FakeUserMemoryRepository();
        redis = mock(StringRedisTemplate.class);
        ZSetOperations<String, String> zset = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zset);
        when(zset.reverseRange(anyString(), any(long.class), any(long.class))).thenReturn(new java.util.HashSet<>());
        @SuppressWarnings("unchecked")
        ObjectProvider<EmbeddingModel> emPr = mock(ObjectProvider.class);
        when(emPr.getIfAvailable()).thenReturn(null);
        @SuppressWarnings("unchecked")
        ObjectProvider<RagService> rgPr = mock(ObjectProvider.class);
        when(rgPr.getIfAvailable()).thenReturn(null);
        cut = new MemoryServiceImpl(repo, redis, emPr, rgPr);
    }

    @Test
    void ac1_越权_查询他人用户_不得返回本人记忆() {
        cut.saveLongTerm(TENANT, "userA", "preference", "偏好经济舱", 0.9, "s1");
        // A 的画像应能被本人读到
        assertEquals(1, cut.retrieveLongTerm(TENANT, "userA", "", null).size());
        // 用 B 的 userId 查询 → 隔离红线：返回 B 的（空）数据，而非 A 的
        List<Map<String, Object>> bMemories = cut.retrieveLongTerm(TENANT, "userB", "", null);
        assertTrue(bMemories.isEmpty(), "不应返回他人（userA）记忆");
    }

    @Test
    void ac2_长期记忆_可被召回供第二场会话组装() {
        cut.saveLongTerm(TENANT, "u7", "preference", "偏好经济舱", 0.9, "s_prev");
        List<Map<String, Object>> mem = cut.retrieveLongTerm(TENANT, "u7", "出行偏好", 5);
        assertEquals(1, mem.size());
        assertEquals("preference", mem.get(0).get("field"));
        assertTrue(mem.get(0).get("value").toString().contains("经济舱"));
    }

    @Test
    void 低置信_进入待确认_确认后转ACTIVE() {
        long id = cut.saveLongTerm(TENANT, "u9", "identity", "可能是项目负责人", 0.4, "s2");
        assertEquals(1, cut.pendingConfirmations(TENANT, "u9", "u9").size());
        cut.confirmMemory(TENANT, "u9", id, true);
        assertEquals(1, cut.retrieveLongTerm(TENANT, "u9", "", null).size());
    }

    @Test
    void 确认他人记忆_越权拒绝() {
        long id = cut.saveLongTerm(TENANT, "uA", "identity", "某身份", 0.4, "s");
        org.junit.jupiter.api.Assertions.assertThrows(com.agent.common.BizException.class,
                () -> cut.confirmMemory(TENANT, "uB", id, true));
    }

    @Test
    void ac4_短期记忆_写入刷新TTL() {
        cut.saveShortTerm(TENANT, "sess1", "user", "你好");
        // 写入必须刷新 Redis 过期（滚动 TTL）
        verify(redis).expire(argThat(k -> k != null && k.contains("sess1")), any());
    }

    /** 内存 fake：覆盖 W7 用到的仓库方法，模拟 DB 状态 */
    static class FakeUserMemoryRepository extends UserMemoryRepository {
        private final List<UserMemoryRow> rows = new java.util.concurrent.CopyOnWriteArrayList<>();
        private long seq;

        FakeUserMemoryRepository() {
            super(null);
        }

        @Override
        public long insert(String tenantId, String userId, String field, String value,
                           double confidence, String status, String source, String embedding) {
            long id = ++seq;
            rows.add(new UserMemoryRow(id, tenantId, userId, field, value, confidence, status, source,
                    Instant.now(), Instant.now()));
            return id;
        }

        @Override
        public Optional<UserMemoryRow> findById(long id) {
            return rows.stream().filter(r -> r.id() == id).findFirst();
        }

        @Override
        public List<UserMemoryRow> selectActive(String tenantId, String userId, Integer topK, String queryEmbedding) {
            return rows.stream().filter(r -> TENANT.equals(r.tenantId()) || tenantId.equals(r.tenantId()))
                    .filter(r -> userId.equals(r.userId()) && "ACTIVE".equals(r.status()))
                    .toList();
        }

        @Override
        public List<UserMemoryRow> selectPending(String tenantId, String userId) {
            return rows.stream().filter(r -> userId.equals(r.userId()) && "PENDING_CONFIRM".equals(r.status()))
                    .toList();
        }

        @Override
        public void setStatus(long id, String status) {
            rows.replaceAll(r -> r.id() == id ? new UserMemoryRow(r.id(), r.tenantId(), r.userId(), r.field(),
                    r.value(), r.confidence(), status, r.source(), r.createdAt(), r.updatedAt()) : r);
        }
    }
}
