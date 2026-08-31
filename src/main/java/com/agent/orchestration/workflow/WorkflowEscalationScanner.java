package com.agent.orchestration.workflow;

import com.agent.data.workflow.WorkflowRepository;
import com.agent.data.workflow.WorkflowRepository.NodeRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 人工节点超时升级扫描器（docs/design/architecture/20260831-workflow-engine.md §2.3）
 * 每 30s 扫一次：查挂起超时且未升级的人工节点 → 标记 escalated_at（防重复）→ 推送 HUMANS_ESCALATED 事件。
 * 升级不改流程走向（实例保持 WAITING_APPROVAL，人工仍可审批）；v1 escalationUrl 回调留待接线。
 */
@Component
public class WorkflowEscalationScanner {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEscalationScanner.class);

    private final WorkflowRepository repo;
    private final WorkflowServiceImpl engine;

    public WorkflowEscalationScanner(WorkflowRepository repo, WorkflowServiceImpl engine) {
        this.repo = repo;
        this.engine = engine;
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void sweep() {
        try {
            for (NodeRow n : repo.findEscalationDue(Instant.now())) {
                repo.markEscalated(n.nodeRunId());
                engine.publishToSink(n.instanceId(), new WorkflowEvent(
                        "HUMAN_ESCALATED", n.instanceId(), n.nodeId(),
                        "WAITING_APPROVAL", "人工审批超时，已升级", Instant.now()));
                log.warn("实例 {} 人工节点 {} 审批超时已升级（escalation_at 已至）", n.instanceId(), n.nodeId());
            }
        } catch (Exception e) {
            log.warn("人工超时升级扫描异常: {}", e.getMessage());
        }
    }
}
