package com.agent.orchestration.workflow;

import com.agent.common.PageResult;
import com.agent.common.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * L3 编排层：Workflow 引擎 REST 接口（docs/design/architecture/20260831-workflow-engine.md §3）
 * 租户隔离：全部读取 X-Tenant-Id（缺省 "default"），同 W4 AgentRunController 风格。
 */
@RestController
@RequestMapping("/api/workflow")
public class WorkflowController {

    private final WorkflowService workflow;
    private final WorkflowFlows flows;

    public WorkflowController(WorkflowService workflow, WorkflowFlows flows) {
        this.workflow = workflow;
        this.flows = flows;
    }

    /** 内置流程列表（v1 目录） */
    @GetMapping("/flows")
    public Result<List<String>> flows() {
        return Result.ok(List.of("trip_booking"));
    }

    /** 内置流程定义 */
    @GetMapping("/flows/{name}")
    public Result<Map<String, Object>> flowDef(@PathVariable String name) {
        return Result.ok(Map.of("name", name, "flowDef", flows.load(name)));
    }

    /** 实例列表（分页，status 可选，与 GET /instances/{instanceId} 由路径区分） */
    @GetMapping("/instances")
    public Result<PageResult<Map<String, Object>>> list(@RequestParam(defaultValue = "1") int page,
                                                        @RequestParam(defaultValue = "20") int size,
                                                        @RequestParam(required = false) String status,
                                                        @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        return Result.ok(workflow.listInstances(tenantId, page, size, status));
    }

    /** 启动流程（flowDef 传入 JSON 或 flowRef 引用内置名） */
    @PostMapping("/instances")
    public Result<Long> start(@Valid @RequestBody StartRequest req,
                              @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        String appId = req.appId() == null ? "TR_BOOKING" : req.appId();
        String flowDef = req.flowDef() != null ? req.flowDef()
                : (req.flowRef() != null ? flows.load(req.flowRef())
                : null);
        if (flowDef == null) {
            return Result.error(400, "flowDef 或 flowRef 必须提供其一");
        }
        return Result.ok(workflow.start(tenantId, appId, flowDef, req.input()));
    }

    /** 实例详情 */
    @GetMapping("/instances/{instanceId}")
    public Result<Map<String, Object>> detail(@PathVariable long instanceId,
                                              @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        return Result.ok(workflow.detail(tenantId, instanceId));
    }

    /** 节点执行历史 */
    @GetMapping("/instances/{instanceId}/nodes")
    public Result<List<Map<String, Object>>> nodeHistory(@PathVariable long instanceId,
                                                         @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        return Result.ok(workflow.nodeHistory(tenantId, instanceId));
    }

    /** 人工审批：{nodeRunId, approved, comment?} */
    @PostMapping("/instances/{instanceId}/approval")
    public Result<Void> approve(@PathVariable long instanceId,
                                @RequestBody ApprovalRequest req,
                                @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        workflow.approve(tenantId, instanceId, req.nodeRunId(), req.approved(), req.comment());
        return Result.ok();
    }

    /** 取消（已完成写节点补偿回滚） */
    @DeleteMapping("/instances/{instanceId}")
    public Result<Void> cancel(@PathVariable long instanceId,
                               @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        workflow.cancel(tenantId, instanceId);
        return Result.ok();
    }

    /** 失败节点单独重试 */
    @PostMapping("/instances/{instanceId}/nodes/{nodeRunId}/retry")
    public Result<Void> retryNode(@PathVariable long instanceId, @PathVariable long nodeRunId,
                                  @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        workflow.retryNode(tenantId, instanceId, nodeRunId);
        return Result.ok();
    }

    /** SSE 实时事件 */
    @GetMapping(value = "/instances/{instanceId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<WorkflowEvent> stream(@PathVariable long instanceId,
                                      @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        return workflow.stream(tenantId, instanceId);
    }

    public record StartRequest(@NotBlank(message = "appId 或 flowDef/flowRef 不能为空") String appId,
                               String flowDef, String flowRef, Map<String, Object> input) {
    }

    public record ApprovalRequest(long nodeRunId, boolean approved, String comment) {
    }
}
