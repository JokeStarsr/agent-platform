package com.agent.orchestration.skillhub;

import com.agent.common.PageResult;
import com.agent.common.Result;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * L3 编排层：通用 AI 助手 API（docs/design/architecture/20260905-skill-hub.md §7.2，统一 Result<T>）。
 */
@RestController
@RequestMapping("/api/assistant")
public class GeneralAssistantController {

    private final GeneralAssistantService service;

    public GeneralAssistantController(GeneralAssistantService service) {
        this.service = service;
    }

    /** 提问（同步返回，v1；SSE 流式留演进） */
    @PostMapping("/ask")
    public Result<Map<String, Object>> ask(@RequestBody Map<String, Object> body,
                                           @RequestHeader("X-Tenant-Id") String tenantId) {
        String message = (String) body.get("message");
        String sessionId = (String) body.get("sessionId");
        String skillHint = (String) body.get("skillHint");
        if (message == null || message.isBlank()) {
            throw new com.agent.common.BizException(400, "message 不能为空");
        }
        return Result.ok(service.ask(tenantId, message, sessionId, skillHint));
    }

    /** 确认写操作预览 */
    @PostMapping("/confirm")
    public Result<Map<String, Object>> confirm(@RequestBody Map<String, Object> body,
                                               @RequestHeader("X-Tenant-Id") String tenantId) {
        String sessionId = (String) body.get("sessionId");
        String previewId = (String) body.get("previewId");
        Boolean approved = (Boolean) body.get("approved");
        if (sessionId == null || previewId == null || approved == null) {
            throw new com.agent.common.BizException(400, "sessionId/previewId/approved 必填");
        }
        return Result.ok(service.confirm(tenantId, sessionId, previewId, approved));
    }

    /** 可用技能清单 */
    @GetMapping("/skills")
    public Result<List<Map<String, Object>>> skills(@RequestHeader("X-Tenant-Id") String tenantId) {
        return Result.ok(service.skills());
    }
}