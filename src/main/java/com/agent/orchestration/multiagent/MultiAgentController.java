package com.agent.orchestration.multiagent;

import com.agent.common.PageResult;
import com.agent.common.Result;
import com.agent.orchestration.multiagent.MultiAgentService.PipelineStage;
import com.agent.orchestration.multiagent.MultiAgentService.SubmitReq;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * L3 编排层：多智能体管理 API（docs/design/architecture/20260905-multi-agent.md §8，统一 Result<T>）。
 */
@RestController
@RequestMapping("/api/multi-agent")
public class MultiAgentController {

    private final MultiAgentService service;
    private final SharedBlackboard blackboard;

    public MultiAgentController(MultiAgentService service, SharedBlackboard blackboard) {
        this.service = service;
        this.blackboard = blackboard;
    }

    /** 提交多智能体任务（supervisor / pipeline） */
    @PostMapping("/runs")
    public Result<Map<String, Object>> submit(@RequestBody SubmitReq req,
                                              @RequestHeader("X-Tenant-Id") String tenantId) {
        long id = service.submit(tenantId, req);
        return Result.ok(Map.of("rootRunId", id));
    }

    /** 根 run 详情 + 子 run 树 + 黑板 */
    @GetMapping("/runs/{id}")
    public Result<Map<String, Object>> detail(@PathVariable long id,
                                              @RequestHeader("X-Tenant-Id") String tenantId) {
        return Result.ok(service.detail(tenantId, id));
    }

    /** 黑板当前键值 */
    @GetMapping("/runs/{id}/board")
    public Result<Map<String, Object>> board(@PathVariable long id,
                                             @RequestHeader("X-Tenant-Id") String tenantId) {
        return Result.ok(blackboard.read(tenantId, id));
    }

    /** 列表（分页/状态） */
    @GetMapping("/runs")
    public Result<PageResult<Map<String, Object>>> list(@RequestParam(defaultValue = "1") int page,
                                                        @RequestParam(defaultValue = "20") int size,
                                                        @RequestParam(required = false) String status,
                                                        @RequestHeader("X-Tenant-Id") String tenantId) {
        return Result.ok(service.list(tenantId, page, size, status));
    }

    /** 取消（取消根 + 黑板清理） */
    @PostMapping("/runs/{id}/cancel")
    public Result<Void> cancel(@PathVariable long id, @RequestHeader("X-Tenant-Id") String tenantId) {
        service.cancel(tenantId, id);
        return Result.ok(null);
    }
}