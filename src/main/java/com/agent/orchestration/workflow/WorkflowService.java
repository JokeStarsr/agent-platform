package com.agent.orchestration.workflow;

import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * L3 编排层：Workflow 引擎接口（docs/design/architecture/20260831-workflow-engine.md §3）
 */
public interface WorkflowService {

    /** 启动流程（定义 JSON 校验 + 入参 → CREATED → 异步执行），返回 instanceId */
    long start(String tenantId, String appId, String flowDefJson, Map<String, Object> input);

    /** 实例详情（状态/当前节点/错误） */
    Map<String, Object> detail(String tenantId, long instanceId);

    /** 节点执行历史（含 attempt 与快照） */
    List<Map<String, Object>> nodeHistory(String tenantId, long instanceId);

    /** 人工节点审批：approved=true 继续，false 驳回并补偿回滚 */
    void approve(String tenantId, long instanceId, long nodeRunId, boolean approved, String comment);

    /** 取消（仅 RUNNING/WAITING_APPROVAL，已完成写节点补偿回滚） */
    void cancel(String tenantId, long instanceId);

    /** 失败节点单独重试（仅 FAILED，新 attempt 行复用配置） */
    void retryNode(String tenantId, long instanceId, long nodeRunId);

    /** SSE 实时事件 */
    Flux<WorkflowEvent> stream(String tenantId, long instanceId);
}
