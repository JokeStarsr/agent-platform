package com.agent.app.admin;

import com.agent.data.memory.UserMemoryRepository;
import com.agent.data.rag.RagCollectionRepository;
import com.agent.data.workflow.WorkflowRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 冲刺 3：保留期自动清理（@Scheduled）
 * <p>
 * - 会话 180 天保留期（超短时记忆）
 * - 删除记录 30 天保留期（审计日志）
 * - Token 数据 180 天保留期
 * - RAG 知识库 180 天保留期
 * </p>
 */
@Service
public class ScheduledCleanupService {

    private static final Logger log = LoggerFactory.getLogger(ScheduledCleanupService.class);
    private static final int SESSION_RETENTION_DAYS = 180;
    private static final int RECORD_RETENTION_DAYS = 30;
    private static final int TOKEN_RETENTION_DAYS = 180;
    private static final int RAG_RETENTION_DAYS = 180;

    private final JdbcTemplate jdbc;
    private final UserMemoryRepository memoryRepo;
    private final RagCollectionRepository ragRepo;
    private final WorkflowRepository workflowRepo;
    private final ScheduledTaskHistory history;

    public ScheduledCleanupService(JdbcTemplate jdbc,
                                  UserMemoryRepository memoryRepo,
                                  RagCollectionRepository ragRepo,
                                  WorkflowRepository workflowRepo,
                                  ScheduledTaskHistory history) {
        this.jdbc = jdbc;
        this.memoryRepo = memoryRepo;
        this.ragRepo = ragRepo;
        this.workflowRepo = workflowRepo;
        this.history = history;
    }

    /**
     * 每日凌晨 3 点执行全量清理
     */
    @Scheduled(cron = "0 0 3 * * ?")
    @Transactional
    public void executeCleanup() {
        Instant start = Instant.now();
        int totalDeleted = 0;

        try {
            int deletedSessions = cleanupSessions();
            int deletedRecords = cleanupRecords();
            int deletedTokens = cleanupTokens();
            int deletedRag = cleanupRag();

            totalDeleted = deletedSessions + deletedRecords + deletedTokens + deletedRag;

            log.info("自动清理完成: 删除{}条记录 (会话: {}, 记录: {}, Token: {}, RAG: {}), 耗时{}ms",
                    totalDeleted, deletedSessions, deletedRecords, deletedTokens, deletedRag,
                    System.currentTimeMillis() - start.toEpochMilli());

            // 记录执行历史
            history.recordTask("auto-cleanup", start, totalDeleted, null);
        } catch (Exception e) {
            log.error("自动清理失败: {}", e.getMessage(), e);
            // 记录失败历史
            history.recordTask("auto-cleanup", start, 0, e.getMessage());
        }
    }

    /**
     * 清理超期的会话（短时记忆，180 天保留）
     */
    private int cleanupSessions() {
        return memoryRepo.deleteOldRecords(SESSION_RETENTION_DAYS);
    }

    /**
     * 清理超期的删除记录（审计日志，30 天保留）
     */
    private int cleanupRecords() {
        // 暂无具体删除审计表，预留接口
        return 0;
    }

    /**
     * 清理超期的 Token 数据（180 天保留）
     */
    private int cleanupTokens() {
        workflowRepo.deleteOldTokenRecords(TOKEN_RETENTION_DAYS);
        return 0; // 实际删除量需从 token repo 获取
    }

    /**
     * 清理超期的 RAG 知识库（180 天保留）
     */
    private int cleanupRag() {
        ragRepo.deleteOldCollections(RAG_RETENTION_DAYS);
        return 0; // 实际删除量需从 rag repo 获取
    }

    /**
     * 立即执行手动触发清理（用于测试）
     */
    @Scheduled(fixedRate = 86400000) // 每天1次，固定频率便于测试
    @Transactional
    public void manualCleanupTrigger() {
        log.info("手动清理触发");
        executeCleanup();
    }
}

/**
 * 清理历史记录（后续可扩展表存储）
 */
class ScheduledTaskHistory {
    // W23 清理历史表，W22 先留接口
    public void recordTask(String taskName, Instant startTime, int deletedRecords, String errorMessage) {
        // TODO W23: t_cleanup_history 记录每次执行状态
        log.info("清理任务: {} 删除{}条，错误: {}", taskName, deletedRecords, errorMessage);
    }
}