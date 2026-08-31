package com.agent.capability.memory;

import java.util.List;
import java.util.Map;

/**
 * L4 能力层：三级记忆服务（docs/design/architecture/20260901-memory-context.md §3.1）
 * 短期（Redis 会话）/ 长期（用户画像向量 + 抽取/确认/遗忘）/ 组织（复用 RAG）。
 */
public interface MemoryService {

    /** 短期：会话轮次追加（Redis ZSET 原子 + TTL 刷新） */
    void saveShortTerm(String tenantId, String sessionId, String role, String content);

    /** 短期：取最近 limit 条会话 */
    List<Map<String, Object>> loadShortTerm(String tenantId, String sessionId, int limit);

    /** 长期：自发写入（confidence<0.7 自动置 PENDING_CONFIRM）返回 id */
    Long saveLongTerm(String tenantId, String userId, String field, String value,
                      double confidence, String source);

    /** 长期：近邻召回用户活跃画像（越权红线：仅返回 tenant+userId 归属数据） */
    List<Map<String, Object>> retrieveLongTerm(String tenantId, String userId, String query, Integer topK);

    /** 长期：待确认画像列表 */
    List<Map<String, Object>> pendingConfirmations(String tenantId, String userId, String operatorUserId);

    /** 长期：确认/拒绝（接受→ACTIVE，拒绝→DELETED）——仅本人可操作 */
    void confirmMemory(String tenantId, String operatorUserId, long memoryId, boolean accept);

    /** 长期：用户遗忘（软删 DELETED）——仅本人可操作 */
    void deleteMemory(String tenantId, String operatorUserId, long memoryId);

    /** 组织：复用 RAG 检索（独立语义） */
    Map<String, Object> orgSearch(String tenantId, String query, int topK);
}
