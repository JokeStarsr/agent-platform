package com.agent.data.rag;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;

/**
 * L7 数据层：RAG 向量数据只读统计（pgvector vector_store 表）
 * 租户隔离机制：同一 vector_store 表 + metadata 写 tenant_id 字段（RagService 打标），
 * 所有统计聚合必须带 tenant_id 过滤，不混租户。
 * 对应 docs/design/api/20260902-admin-pages.md §2.4
 */
@Repository
public class RagCollectionRepository {

    private final JdbcTemplate jdbc;

    public RagCollectionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 知识库统计行 */
    public record CollectionStat(String collectionName, String tenantId,
                                 long chunkCount, long docCount, Instant lastUpdate) {
    }

    private static final String STAT_SQL = """
            SELECT count(*)                       AS chunk_count,
                   count(DISTINCT metadata->>'source') AS doc_count,
                   max((metadata->>'indexed_at')::bigint) AS last_update_ms
            FROM vector_store
            WHERE metadata->>'tenant_id' = ?
            """;

    /** 某租户的向量数据统计（只读；存量切片无 indexed_at 时 lastUpdate 为 null） */
    public CollectionStat statByTenant(String tenantId) {
        return jdbc.queryForObject(STAT_SQL, (rs, i) -> {
            long chunkCount = rs.getLong("chunk_count");
            long docCount = rs.getLong("doc_count");
            long lastUpdateMs = rs.getLong("last_update_ms");
            Instant lastUpdate = rs.wasNull() ? null : Instant.ofEpochMilli(lastUpdateMs);
            return new CollectionStat("vector_store", tenantId, chunkCount, docCount, lastUpdate);
        }, tenantId);
    }
}