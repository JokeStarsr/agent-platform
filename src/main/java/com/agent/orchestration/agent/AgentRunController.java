package com.agent.orchestration.agent;

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
 * L3 编排层：Agent Runtime REST 接口（docs/design/architecture/20260831-agent-runtime.md §3）
 * 租户隔离：全部读取 X-Tenant-Id（缺省 "default"），校验归属。
 * 与 capability 层控制器同风格：走 Result&lt;T&gt; 统一响应。
 */
@RestController
@RequestMapping("/api/agent/runs")
public class AgentRunController {

    private final AgentRuntimeService agentRuntime;

    public AgentRunController(AgentRuntimeService agentRuntime) {
        this.agentRuntime = agentRuntime;
    }

    /** 运行列表（分页，status 可选，默认 / 与 GET /{runId} 由路径区分） */
    @GetMapping
    public Result<PageResult<Map<String, Object>>> list(@RequestParam(defaultValue = "1") int page,
                                                        @RequestParam(defaultValue = "20") int size,
                                                        @RequestParam(required = false) String status,
                                                        @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        return Result.ok(agentRuntime.listRuns(tenantId, page, size, status));
    }

    /** 提交任务，返回 runId（异步执行，进度走 stream） */
    @PostMapping
    public Result<Long> submit(@Valid @RequestBody SubmitRequest req,
                               @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        long runId = agentRuntime.submit(tenantId, req.appId(), req.task(), null);
        return Result.ok(runId);
    }

    /** 运行详情（状态/步数/预算/trace 摘要） */
    @GetMapping("/{runId}")
    public Result<Map<String, Object>> detail(@PathVariable long runId,
                                              @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        return Result.ok(agentRuntime.detail(tenantId, runId));
    }

    /** 取消运行（RUNNING / WAITING_APPROVAL） */
    @DeleteMapping("/{runId}")
    public Result<Void> cancel(@PathVariable long runId,
                               @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        agentRuntime.cancel(tenantId, runId);
        return Result.ok();
    }

    /** 写操作 HITL 审批：{approved: true|false} */
    @PostMapping("/{runId}/approval")
    public Result<Void> approve(@PathVariable long runId,
                                @RequestBody ApprovalRequest req,
                                @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        agentRuntime.approve(tenantId, runId, req.approved());
        return Result.ok();
    }

    /** 按序回放事件流 */
    @GetMapping("/{runId}/replay")
    public Result<List<Map<String, Object>>> replay(@PathVariable long runId,
                                                    @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        return Result.ok(agentRuntime.replay(tenantId, runId));
    }

    /** SSE 实时事件（进行中运行的前端进度） */
    @GetMapping(value = "/{runId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<AgentTraceEvent> stream(@PathVariable long runId,
                                        @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        return agentRuntime.stream(tenantId, runId);
    }

    /** 重试（TIMEOUT / FAILED 可重试，新 run 复用原配置） */
    @PostMapping("/{runId}/retry")
    public Result<Long> retry(@PathVariable long runId,
                              @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        return Result.ok(agentRuntime.retry(tenantId, runId));
    }

    public record SubmitRequest(@NotBlank(message = "task 不能为空") String task, String appId) {
        public SubmitRequest {
            if (appId == null) {
                appId = "CS_AGENT";
            }
        }
    }

    public record ApprovalRequest(boolean approved) {
    }
}