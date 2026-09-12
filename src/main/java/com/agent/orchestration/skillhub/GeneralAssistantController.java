package com.agent.orchestration.skillhub;

import com.agent.common.Result;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * L3 编排层：通用 AI 助手控制器（写操作预览前后端打通）
 * <p>契约（与 static/assistant/app.js 对齐）：
 *   POST /api/assistant/ask      {message, sessionId?, skillHint?}
 *   POST /api/assistant/confirm  {sessionId, previewId, approved}
 *   GET  /api/assistant/skills
 *   GET  /api/assistant/preview/{previewId}
 * </p>
 */
@RestController
@RequestMapping("/api/assistant")
public class GeneralAssistantController {

    private final GeneralAssistantService service;

    public GeneralAssistantController(GeneralAssistantService service) {
        this.service = service;
    }

    /**
     * 提问入口：可能返回预览要求（PREVIEW_REQUIRED）
     */
    @PostMapping("/ask")
    public Result<Map<String, Object>> ask(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        String message = String.valueOf(body.getOrDefault("message", ""));
        String sessionId = (String) body.get("sessionId");
        String skillHint = (String) body.get("skillHint");
        Map<String, Object> result = service.ask(tenantId, message, sessionId, skillHint);
        return Result.ok(result);
    }

    /**
     * 确认写操作预览（用户点击"执行/拒绝"按钮后调用）
     */
    @PostMapping("/confirm")
    public Result<Map<String, Object>> confirm(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        String sessionId = (String) body.get("sessionId");
        String previewId = (String) body.get("previewId");
        boolean approved = Boolean.TRUE.equals(body.get("approved"));

        if (!approved) {
            Map<String, Object> result = Map.of(
                    "sessionId", sessionId,
                    "status", "CANCELLED",
                    "message", "用户取消了写操作"
            );
            return Result.ok(result);
        }

        Map<String, Object> result = service.confirm(tenantId, sessionId, previewId, true);
        return Result.ok(result);
    }

    /**
     * 可用技能清单（前端"可用技能"卡片）
     */
    @GetMapping("/skills")
    public Result<List<Map<String, Object>>> skills(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        return Result.ok(service.skills());
    }

    /**
     * 获取预览详情（前端预览页查询）
     */
    @GetMapping("/preview/{previewId}")
    public Result<Map<String, Object>> getPreview(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String previewId) {
        var previewOpt = service.getPreview(previewId);
        if (previewOpt.isEmpty()) {
            return Result.error(404, "预览不存在或已过期");
        }
        var preview = previewOpt.get();
        Map<String, Object> detail = Map.of(
                "previewId", preview.previewId(),
                "tenantId", tenantId,
                "tool", preview.toolName(),
                "params", preview.args(),
                "summary", preview.summary(),
                "impact", preview.impact()
        );
        return Result.ok(detail);
    }
}