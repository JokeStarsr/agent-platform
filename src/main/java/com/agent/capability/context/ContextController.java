package com.agent.capability.context;

import com.agent.capability.AppPromptProvider;
import com.agent.capability.memory.MemoryService;
import com.agent.common.Result;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * L4 能力层：上下文组装 REST 接口（docs/design/architecture/20260901-memory-context.md §2.4/§3.2）
 * /api/context/assemble：采集会话历史+长期记忆+外部 RAG（可选）→ 按预算组装成 system+user，返回 usage/压缩动作。
 * P1 二期（§2.5）：SYSTEM 取应用 prompt（AssembleReq.appId 非空时）；否则用原硬编码兜底。
 */
@RestController
@RequestMapping("/api/context")
public class ContextController {

    private static final String DEFAULT_SYSTEM = "你是企业级智能体助手。依据提供的知识库上下文与用户记忆作答，仅基于给出信息，不臆造；涉及敏感/政策问题低置信时提示转人工。";

    private final ContextAssembler assembler;
    private final MemoryService memory;
    private final AppPromptProvider appPromptProvider;

    public ContextController(ContextAssembler assembler, MemoryService memory, AppPromptProvider appPromptProvider) {
        this.assembler = assembler;
        this.memory = memory;
        this.appPromptProvider = appPromptProvider;
    }

    @PostMapping("/assemble")
    public Result<Map<String, Object>> assemble(@RequestBody AssembleReq req,
                                                @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
                                                @RequestHeader(value = "X-User-Id", defaultValue = "u_anon") String operator) {
        String userId = req.userId() == null ? operator : req.userId();
        String sessionId = req.sessionId() == null ? "s_" + userId : req.sessionId();

        List<String> history = new ArrayList<>();
        for (Map<String, Object> h : memory.loadShortTerm(tenantId, sessionId, 20)) {
            history.add(h.get("role") + ": " + h.getOrDefault("content", ""));
        }
        List<String> memories = new ArrayList<>();
        for (Map<String, Object> m : memory.retrieveLongTerm(tenantId, userId, req.task() == null ? "" : req.task(), 5)) {
            memories.add("[" + m.get("field") + "] " + m.get("value"));
        }
        List<String> ragHits = req.ragHits() == null ? List.of() : req.ragHits();
        List<String> tools = req.tools() == null ? List.of() : req.tools();

        String system = DEFAULT_SYSTEM;
        if (req.appId() != null && !req.appId().isBlank()) {
            AppPromptProvider.AppPrompt app = appPromptProvider.resolve(tenantId, req.appId());
            if (app.systemPrompt() != null && !app.systemPrompt().isBlank()) {
                system = app.systemPrompt();
            }
        }

        ContextAssembler.ContextBundle b = assembler.assemble(system, req.task(), history, ragHits, memories, tools,
                req.budget() == null ? 0 : req.budget());
        return Result.ok(Map.of("system", b.system(), "user", b.user(), "usage", b.usage(), "actions", b.actions()));
    }

    public record AssembleReq(String appId, String sessionId, String userId, String task,
                              List<String> ragHits, List<String> tools, Integer budget) {
    }
}
