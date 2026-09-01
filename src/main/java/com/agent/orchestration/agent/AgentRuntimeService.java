package com.agent.orchestration.agent;

import com.agent.common.PageResult;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * L3 编排层：Agent Runtime 对外接口（docs/design/architecture/20260831-agent-runtime.md §3）
 * v1 同步提交异步执行：submit 返回 runId，进度经 stream() 实时订阅。
 */
public interface AgentRuntimeService {

    /** 运行列表（租户范围，状态可选筛选，page/size 在 Repository 层 clamp） */
    PageResult<Map<String, Object>> listRuns(String tenantId, int page, int size, String status);

    /** 提交任务，返回 runId（CREATED → 异步 RUNNING） */
    long submit(String tenantId, String appId, String task, AgentConfig config);

    /** 运行详情（状态/步数/预算/trace 摘要） */
    Map<String, Object> detail(String tenantId, long runId);

    /** 取消运行（仅 RUNNING/WAITING_APPROVAL 可取消） */
    void cancel(String tenantId, long runId);

    /** 写操作 HITL 审批：approved=true 执行，false 跳过该写操作 */
    void approve(String tenantId, long runId, boolean approved);

    /** 按序回放事件流 */
    List<Map<String, Object>> replay(String tenantId, long runId);

    /** SSE 实时事件（进行中运行的前端进度展示） */
    Flux<AgentTraceEvent> stream(String tenantId, long runId);

    /** 重试（仅 TIMEOUT/FAILED 可重试，新 run 复用原配置） */
    long retry(String tenantId, long runId);
}