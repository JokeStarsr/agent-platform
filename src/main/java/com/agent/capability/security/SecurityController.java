package com.agent.capability.security;

import com.agent.common.Result;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * L4 能力层：安全防护 API（W20，安全红线）
 * <p>提供 Prompt 注入检测、PII 脱敏、检测日志/规则查询等接口。</p>
 */
@RestController
@RequestMapping("/api/security")
public class SecurityController {

    private final PromptInjectionDetector injectionDetector;
    private final PiiMasker piiMasker;

    public SecurityController(PromptInjectionDetector injectionDetector, PiiMasker piiMasker) {
        this.injectionDetector = injectionDetector;
        this.piiMasker = piiMasker;
    }

    /**
     * 检测文本是否包含 Prompt 注入。
     */
    @PostMapping("/injection/detect")
    public Result<PromptInjectionDetector.DetectionResult> detectInjection(
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {

        String text = body.getOrDefault("text", "");
        String source = body.getOrDefault("source", "api");

        PromptInjectionDetector.DetectionResult result = injectionDetector.detect(text, source);

        // 规则未命中 → 可选 LLM 语义补充
        if (!result.blocked() && body.containsKey("useLlm") && "true".equalsIgnoreCase(body.get("useLlm"))) {
            // LLM 检测需要注入 LlmGateway，通过额外接口暴露（避免循环依赖）
            return Result.ok(result);
        }

        return Result.ok(result);
    }

    /**
     * 对文本进行 PII 脱敏。
     */
    @PostMapping("/pii/mask")
    public Result<PiiMasker.MaskResult> maskPii(@RequestBody Map<String, String> body) {
        String text = body.getOrDefault("text", "");
        return Result.ok(piiMasker.mask(text));
    }

    /**
     * 判断文本是否包含 PII。
     */
    @PostMapping("/pii/check")
    public Result<Map<String, Object>> checkPii(@RequestBody Map<String, String> body) {
        String text = body.getOrDefault("text", "");
        boolean contains = piiMasker.containsPii(text);
        return Result.ok(Map.of("containsPii", contains));
    }

    /**
     * 获取最近注入检测日志。
     */
    @GetMapping("/injection/logs")
    public Result<List<Map<String, Object>>> injectionLogs(
            @RequestParam(defaultValue = "50") int limit) {
        return Result.ok(injectionDetector.recentLogs(Math.min(limit, 500)));
    }

    /**
     * 获取注入检测规则列表。
     */
    @GetMapping("/injection/rules")
    public Result<List<Map<String, String>>> injectionRules() {
        return Result.ok(injectionDetector.rules());
    }
}