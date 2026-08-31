package com.agent.orchestration.workflow;

import com.agent.data.workflow.WorkflowRepository;
import com.agent.data.workflow.WorkflowRepository.InstanceRow;
import com.agent.data.workflow.WorkflowRepository.NodeRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

/**
 * 人工节点超时升级扫描器 v2（docs/design/architecture/20260831-w8-trip-scenario.md §2.5）
 * <p>每 30s 扫挂起超时且未升级的人工节点：
 * <ol>
 *  <li>POST 流程定义配置的 escalationUrl（幂等：回调 2xx 才置 escalated_at，失败下轮重试；escalated_at 已置不重发）</li>
 *  <li>推送 HUMAN_ESCALATED 事件。升级不改流程走向（实例保持 WAITING_APPROVAL，人工仍可审批）。</li>
 * </ol></p>
 */
@Component
public class WorkflowEscalationScanner {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEscalationScanner.class);
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();

    private final WorkflowRepository repo;
    private final WorkflowDefParser parser;
    private final WorkflowServiceImpl engine;

    public WorkflowEscalationScanner(WorkflowRepository repo, WorkflowDefParser parser, WorkflowServiceImpl engine) {
        this.repo = repo;
        this.parser = parser;
        this.engine = engine;
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void sweep() {
        try {
            for (NodeRow n : repo.findEscalationDue(Instant.now())) {
                escalate(n);
            }
        } catch (Exception e) {
            log.warn("人工超时升级扫描异常: {}", e.getMessage());
        }
    }

    private void escalate(NodeRow n) {
        Instant now = Instant.now();
        engine.publishToSink(n.instanceId(), new WorkflowEvent(
                "HUMAN_ESCALATED", n.instanceId(), n.nodeId(),
                "WAITING_APPROVAL", "人工审批超时，已升级", now));
        log.warn("实例 {} 人工节点 {} 审批超时已升级", n.instanceId(), n.nodeId());

        String url = escalationUrlOf(n.instanceId());
        if (url == null || url.isBlank()) {
            repo.markEscalated(n.nodeRunId()); // 无回调即完成升级标记
            return;
        }
        if (postCallback(url, n)) {
            repo.markEscalated(n.nodeRunId());
        } else {
            log.warn("升级回调失败，下轮扫描重试: {} （escalated_at 未置）", url);
        }
    }

    /** 回调仅 2xx 算成功；超时/非 2xx 不置 escalated_at → 下轮重试（幂等一次语义） */
    private boolean postCallback(String url, NodeRow n) {
        try {
            String body = "{\"instanceId\":" + n.instanceId()
                    + ",\"nodeId\":\"" + n.nodeId()
                    + "\",\"escalationTime\":\"" + Instant.now() + "\""
                    + ",\"tenantId\":\"" + tenantOf(n) + "\"}";
            HttpResponse<String> resp = HTTP.send(HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .timeout(Duration.ofSeconds(3))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() >= 200 && resp.statusCode() < 300;
        } catch (Exception e) {
            log.warn("升级回调调用异常: {}", e.getMessage());
            return false;
        }
    }

    private String escalationUrlOf(long instanceId) {
        try {
            InstanceRow inst = repo.findInstance(instanceId).orElse(null);
            if (inst == null) {
                return null;
            }
            return parser.parse(inst.flowDef()).escalationUrl();
        } catch (Exception e) {
            return null;
        }
    }

    private String tenantOf(NodeRow n) {
        return repo.findInstance(n.instanceId()).map(InstanceRow::tenantId).orElse("default");
    }
}