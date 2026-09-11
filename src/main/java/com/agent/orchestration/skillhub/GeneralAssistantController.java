package com.agent.orchestration.skillhub;

import com.agent.common.Result;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * L3 编排层：通用 AI 助手控制器（写操作预览前后端打通）
 * <p>提供：/ask 预览生成 → /preview/confirm 确认执行</p>
 */
@RestController
@RequestMapping("/api/skill-hub")
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
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @RequestParam String message,
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false) String skillHint
    ) {
        Map<String, Object> result = service.ask(tenantId, message, sessionId, skillHint);
        return Result.ok(result);
    }

    /**
     * 确认写操作预览（用户点击"执行"按钮后调用）
     */
    @PostMapping("/preview/confirm")
    public Result<Map<String, Object>> confirmPreview(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @RequestParam String sessionId,
            @RequestParam String previewId,
            @RequestParam boolean approved
    ) {
        if (!approved) {
            // 用户拒绝，返回取消结果
            Map<String, Object> result = Map.of(
                    "sessionId", sessionId,
                    "status", "CANCELLED",
                    "message", "用户取消了写操作"
            );
            return Result.ok(result);
        }

        // 用户确认，继续执行
        Map<String, Object> result = service.confirm(tenantId, sessionId, previewId, true);
        return Result.ok(result);
    }

    /**
     * 获取预览详情（前端预览页查询）
     */
    @GetMapping("/preview/{previewId}")
    public Result<Map<String, Object>> getPreview(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String previewId
    ) {
        var previewOpt = service.getPreview(previewId);
        if (previewOpt.isEmpty()) {
            return Result.error(404, "预览不存在或已过期");
        }

        var preview = previewOpt.get();
        Map<String, Object> detail = Map.of(
                "previewId", preview.previewId(),
                "tenantId", tenantId,
                "tool", preview.tool(),
                "params", preview.params(),
                "summary", preview.summary(),
                "impact", preview.impact()
        );
        return Result.ok(detail);
    }
}
