package com.agent.app.admin;

import com.agent.capability.dataagent.ResultCache;
import com.agent.data.agentrun.AgentRunRepository;
import com.agent.data.tokenmeter.TokenUsageDailyRepository;
import com.agent.data.tokenmeter.TokenUsageRepository;
import com.agent.data.workflow.WorkflowRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 冲刺 2：删除级联服务（用户数据一键清理）
 * <p>跨表删除：记忆/向量/缓存/会话/Token/Agent运行/Workflow实例/血缘。</p>
 */
@Service
public class DataPurgeService {

    private final JdbcTemplate jdbc;
    private final ResultCache cache;
    private final AgentRunRepository agentRunRepo;
    private final WorkflowRepository workflowRepo;
    private final TokenUsageRepository tokenUsageRepo;
    private final TokenUsageDailyRepository tokenUsageDailyRepo;

    public DataPurgeService(JdbcTemplate jdbc, ResultCache cache,
                           AgentRunRepository agentRunRepo, WorkflowRepository workflowRepo,
                           TokenUsageRepository tokenUsageRepo, TokenUsageDailyRepository tokenUsageDailyRepo) {
        this.jdbc = jdbc;
        this.cache = cache;
        this.agentRunRepo = agentRunRepo;
        this.workflowRepo = workflowRepo;
        this.tokenUsageRepo = tokenUsageRepo;
        this.tokenUsageDailyRepo = tokenUsageDailyRepo;
    }

    /**
     * 删除租户所有数据（级联）
     */
    @Transactional
    public void purgeTenant(String tenantId) {
        // 1. 记忆表
        jdbc.update("DELETE FROM t_user_memory WHERE tenant_id = ?", tenantId);

        // 2. 数据血缘
        jdbc.update("DELETE FROM t_data_lineage WHERE tenant_id = ?", tenantId);

        // 3. Token 使用量（当日 + 历史累计）
        tokenUsageRepo.deleteByTenant(tenantId);
        tokenUsageDailyRepo.deleteByTenant(tenantId);

        // 4. Agent 运行记录（包括步骤记录）
        jdbc.update("DELETE FROM t_agent_run_steps WHERE agent_run_id IN " +
                "(SELECT id FROM t_agent_run WHERE tenant_id = ?)", tenantId);
        agentRunRepo.deleteByTenant(tenantId);

        // 5. Workflow 实例
        workflowRepo.deleteByTenant(tenantId);

        // 6. RAG 知识库（向量库 per-tenant 清理，直接删除 collection）
        jdbc.update("DROP SCHEMA IF EXISTS vector_store_" + tenantId + " CASCADE");

        // 7. 缓存结果（高频问缓存）
        cache.clearTenant(tenantId);

        // 8. Redis 会话（短时记忆，约定 key）
        purgeSessions(tenantId);
    }

    /**
     * 删除指定用户的所有数据（跨表）
     */
    @Transactional
    public void purgeUser(String tenantId, String userId) {
        // 1. 用户记忆
        jdbc.update("DELETE FROM t_user_memory WHERE tenant_id = ? AND user_id = ?", tenantId, userId);

        // 2. 用户血缘记录
        jdbc.update("DELETE FROM t_data_lineage WHERE tenant_id = ? AND user_id = ?", tenantId, userId);

        // 3. 用户专属 Agent 运行（如用户会话 Agent）
        // Agent 运行表有 tenant_id + user_id 联合索引，可直接过滤
        jdbc.update("DELETE FROM t_agent_run_steps WHERE agent_run_id IN " +
                "(SELECT id FROM t_agent_run WHERE tenant_id = ? AND user_id = ?)", tenantId, userId);
        agentRunRepo.deleteByTenantAndUser(tenantId, userId);

        // 4. 用户专属 Workflow
        workflowRepo.deleteByTenantAndUser(tenantId, userId);

        // 5. 清除用户结果缓存（可能存在用户专属高频问缓存）
        purgeSessions(tenantId, userId);
    }

    /**
     * 清理 Redis 会话（短时记忆）占位：ResultCache 仅缓存 NL2SQL 结果（key 为哈希），
     * Redis 会话清理由基础设施 RedisTemplate 按 key 前缀删除（W23 接入 spring-data-redis 后实现）。
     */
    private void purgeSessions(String tenantId) {
        purgeSessions(tenantId, null);
    }

    private void purgeSessions(String tenantId, String userId) {
        // TODO(W23)：接入 RedisTemplate，按 "session:{tenant}:{user}:" / "memory:{tenant}:{user}:" 前缀删除
    }
}