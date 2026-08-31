package com.agent.capability.memory;

import com.agent.common.Result;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * L4 能力层：三级记忆 REST 接口（docs/design/architecture/20260901-memory-context.md §3.1）
 * 短期（Redis 会话）/ 长期（画像）/ 组织（复用 RAG）。
 * 操作者身份走 X-User-Id 头（缺省 u_anon），长期记忆读写校验归属（越权红线）。
 */
@RestController
@RequestMapping("/api/memory")
public class MemoryController {

    private final MemoryService memory;

    public MemoryController(MemoryService memory) {
        this.memory = memory;
    }

    @PostMapping("/short")
    public Result<Void> saveShortTerm(@RequestBody ShortReq req,
                                      @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        memory.saveShortTerm(tenantId, req.sessionId(), req.role(), req.content());
        return Result.ok();
    }

    @GetMapping("/short")
    public Result<List<Map<String, Object>>> loadShortTerm(@RequestParam String sessionId,
                                                           @RequestParam(defaultValue = "20") int limit,
                                                           @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        return Result.ok(memory.loadShortTerm(tenantId, sessionId, limit));
    }

    @PostMapping("/long")
    public Result<Long> saveLongTerm(@RequestBody LongReq req,
                                     @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
                                     @RequestHeader(value = "X-User-Id", defaultValue = "u_anon") String operator) {
        String userId = req.userId() == null || req.userId().isBlank() ? operator : req.userId();
        double confidence = req.confidence() == null ? 0.9 : req.confidence();
        return Result.ok(memory.saveLongTerm(tenantId, userId, req.field(), req.value(), confidence, req.source()));
    }

    @GetMapping("/user/{userId}")
    public Result<List<Map<String, Object>>> retrieve(@PathVariable String userId,
                                                      @RequestParam(required = false) String query,
                                                      @RequestParam(required = false) Integer topK,
                                                      @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        return Result.ok(memory.retrieveLongTerm(tenantId, userId, query, topK));
    }

    @GetMapping("/pending")
    public Result<List<Map<String, Object>>> pending(@RequestParam String userId,
                                                     @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
                                                     @RequestHeader(value = "X-User-Id", defaultValue = "u_anon") String operator) {
        return Result.ok(memory.pendingConfirmations(tenantId, userId, operator));
    }

    @PostMapping("/{id}/confirm")
    public Result<Void> confirm(@PathVariable long id, @RequestParam boolean accept,
                                @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
                                @RequestHeader(value = "X-User-Id", defaultValue = "u_anon") String operator) {
        memory.confirmMemory(tenantId, operator, id, accept);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable long id,
                               @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
                               @RequestHeader(value = "X-User-Id", defaultValue = "u_anon") String operator) {
        memory.deleteMemory(tenantId, operator, id);
        return Result.ok();
    }

    @GetMapping("/org")
    public Result<Map<String, Object>> orgSearch(@RequestParam String query,
                                                 @RequestParam(defaultValue = "3") int topK,
                                                 @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        return Result.ok(memory.orgSearch(tenantId, query, topK));
    }

    /** 落地页演示：返回三级记忆概览（vs 落地页 /api/memory/demo 文案承接） */
    @GetMapping("/demo")
    public Result<Map<String, Object>> demo(@RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
                                            @RequestHeader(value = "X-User-Id", defaultValue = "demo_user") String operator) {
        Map<String, Object> sessionShort = Map.of(
                "note", "短期记忆：POST /api/memory/short 写入 → GET /api/memory/short?sessionId= 读取（TTL 30min）");
        Map<String, Object> longDesc = Map.of(
                "note", "长期记忆：POST /api/memory/long 写入 → GET /api/memory/user/{userId} 读取 → /pending + /confirm 走确认流");
        Map<String, Object> org = Map.of(
                "note", "组织记忆：GET /api/memory/org?query= 复用 RAG 检索");
        return Result.ok(Map.of("short", sessionShort, "long", longDesc, "org", org,
                "tenant", tenantId, "operator", operator, "assembler", "/api/context/assemble"));
    }

    public record ShortReq(String sessionId, String role, String content) {
    }

    public record LongReq(String userId, String field, String value, Double confidence, String source) {
    }
}
