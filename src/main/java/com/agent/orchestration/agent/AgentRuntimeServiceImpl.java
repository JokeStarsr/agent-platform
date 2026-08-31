package com.agent.orchestration.agent;

import com.agent.common.BizException;
import com.agent.data.agentrun.AgentRunRepository;
import com.agent.model.llm.LlmGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * L3 编排层：Agent Runtime 核心（docs/design/architecture/20260831-agent-runtime.md §2）
 * <p>ReAct 循环（PLAN→[写操作HITL]→ACT→OBSERVE→REFLECT）在护栏内异步执行，
 * 每步事件落 t_agent_step 并推送 SSE；工具执行在编排层手动进行（v1，见 LlmGateway.generateStructured 注释）。</p>
 */
@Service
public class AgentRuntimeServiceImpl implements AgentRuntimeService {

    private static final Logger log = LoggerFactory.getLogger(AgentRuntimeServiceImpl.class);

    // v1 步耗估算：generateStructured 不返回 usage，L6 计量拦截器落地前用固定值近似（budget 语义校准用）
    private static final int LLM_STEP_TOKENS = 500;
    private static final long APPROVAL_TIMEOUT_MS = 600_000; // HITL 审批 10 分钟超时
    private static final int INVALID_TOOL_MAX = 2;            // 非法工具名/参数重试上限
    private static final int LLM_FAIL_MAX = 3;                // LLM 连续失败上限
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AgentRunRepository repo;
    private final LlmGateway llm;
    private final Map<String, AgentTool> tools;

    private final ExecutorService executor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "agent-runtime");
        t.setDaemon(true);
        return t;
    });
    private final Map<Long, Sinks.Many<AgentTraceEvent>> sinks = new ConcurrentHashMap<>();
    private final Map<Long, CompletableFuture<Boolean>> approvals = new ConcurrentHashMap<>();
    private final Map<Long, Boolean> cancelFlags = new ConcurrentHashMap<>();

    public AgentRuntimeServiceImpl(AgentRunRepository repo, LlmGateway llm, List<AgentTool> toolList) {
        this.repo = repo;
        this.llm = llm;
        this.tools = toolList.stream().collect(Collectors.toMap(AgentTool::name, Function.identity()));
    }

    /** 重启后 WAITING_APPROVAL 的 run 审批 future 丢失，置 FAILED 避免永久挂起 */
    @PostConstruct
    void recoverStuckApprovals() {
        List<Long> stuck = repo.findStuckPendingAllTenants();
        for (Long runId : stuck) {
            repo.updateStatus(runId, "FAILED", "服务重启导致审批中断，该运行已终止", 0, 0);
            log.warn("agent run {} 审批中断被终止（重启恢复）", runId);
        }
    }

    /* ---------- 接口实现 ---------- */

    @Override
    public long submit(String tenantId, String appId, String task, AgentConfig config) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new BizException(400, "缺少租户标识 X-Tenant-Id");
        }
        if (task == null || task.isBlank()) {
            throw new BizException(400, "任务不能为空");
        }
        AgentConfig c = config != null ? config : AgentConfig.of(appId);
        if (repo.countRunning(tenantId) >= c.maxConcurrency()) {
            throw new BizException(429, "该租户达到并发执行上限（" + c.maxConcurrency() + "），请稍后再试");
        }
        String traceId = UUID.randomUUID().toString();
        long runId = repo.createRun(tenantId, appId, task, AgentRunStatus.CREATED.name(),
                c.maxSteps(), c.tokenBudget(), c.timeoutMs(), c.loopThreshold(), traceId);
        executor.submit(() -> executeLoop(runId, tenantId, task, c, traceId));
        return runId;
    }

    @Override
    public Map<String, Object> detail(String tenantId, long runId) {
        Map<String, Object> d = repo.runDetail(runId);
        if (d == null) {
            throw new BizException(404, "运行不存在: " + runId);
        }
        checkTenant(d, tenantId);
        return d;
    }

    @Override
    public void cancel(String tenantId, long runId) {
        Map<String, Object> d = requireRun(runId, tenantId, "RUNNING", "WAITING_APPROVAL");
        String status = (String) d.get("status");
        if ("WAITING_APPROVAL".equals(status)) {
            CompletableFuture<Boolean> f = approvals.remove(runId);
            if (f != null) {
                f.complete(false);
            }
        }
        cancelFlags.put(runId, true);
        repo.updateStatus(runId, AgentRunStatus.CANCELED.name(), "用户取消", (Integer) d.get("stepsDone"), (Integer) d.get("tokensUsed"));
    }

    @Override
    public void approve(String tenantId, long runId, boolean approved) {
        AgentRunRepository.PendingApproval p = repo.findPending(runId)
                .orElseThrow(() -> new BizException(404, "该运行无待审批的写操作"));
        if (!p.tenantId().equals(tenantId)) {
            throw new BizException(403, "租户与运行不匹配");
        }
        CompletableFuture<Boolean> f = approvals.get(runId);
        if (f == null) {
            throw new BizException(409, "审批已超时或运行已结束");
        }
        f.complete(approved);
        repo.resumeFromApproval(runId, AgentRunStatus.RUNNING.name());
    }

    @Override
    public List<Map<String, Object>> replay(String tenantId, long runId) {
        requireRun(runId, tenantId);
        return repo.replaySteps(runId);
    }

    @Override
    public Flux<AgentTraceEvent> stream(String tenantId, long runId) {
        requireRun(runId, tenantId);
        Sinks.Many<AgentTraceEvent> sink = sinkOf(runId);
        return sink.asFlux();
    }

    @Override
    public long retry(String tenantId, long runId) {
        Map<String, Object> d = requireRun(runId, tenantId, "TIMEOUT", "FAILED");
        String appId = (String) d.get("appId");
        String task = (String) d.get("task");
        AgentConfig c = new AgentConfig((Integer) d.get("maxSteps"), (Integer) d.get("tokenBudget"),
                (Integer) d.get("timeoutMs"), 3, AgentConfig.of(appId).maxConcurrency());
        return submit(tenantId, appId, task, c);
    }

    /* ---------- 核心循环 ---------- */

    private void executeLoop(long runId, String tenantId, String task, AgentConfig c, String traceId) {
        long startNs = System.nanoTime();
        int steps = 0;
        int tokens = 0;
        int llmFails = 0;
        int invalidTool = 0;
        List<String> history = new ArrayList<>();
        // 循环检测：近 loopThreshold 次工具调用签名（tool|argsHash），全部相同则强制反思一轮，再犯终止
        Deque<String> recentSigs = new ArrayDeque<>();
        boolean loopWarned = false;

        repo.markStarted(runId, 0);
        log.info("agent run {} 启动 task={}", runId, task);

        try {
            while (true) {
                long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;
                GuardrailChecker.Verdict v = GuardrailChecker.check(
                        steps, c.maxSteps(), tokens, c.tokenBudget(), elapsedMs, c.timeoutMs());
                if (v != GuardrailChecker.Verdict.OK) {
                    String reason = switch (v) {
                        case MAX_STEPS -> "已达最大步数（" + c.maxSteps() + "）仍未完成任务";
                        case BUDGET_EXHAUSTED -> "Token 预算（" + c.tokenBudget() + "）已耗尽";
                        case TIMEOUT -> "执行超时（" + c.timeoutMs() + "ms）";
                        default -> "执行被终止";
                    };
                    finish(runId, v.name(), reason, steps, tokens);
                    return;
                }
                if (Boolean.TRUE.equals(cancelFlags.get(runId))) {
                    finish(runId, AgentRunStatus.CANCELED.name(), "用户取消", steps, tokens);
                    return;
                }

                // ---- PLAN：LLM 决策 ----
                AgentStepIntent intent;
                long t0 = System.nanoTime();
                try {
                    intent = llm.generateStructured(buildSystemPrompt(c), buildUserPrompt(task, history), AgentStepIntent.class);
                } catch (Exception e) {
                    llmFails++;
                    log.warn("agent run {} LLM 调用失败({}/{}): {}", runId, llmFails, LLM_FAIL_MAX, e.getMessage());
                    if (llmFails >= LLM_FAIL_MAX) {
                        finish(runId, AgentRunStatus.FAILED.name(), "LLM 连续失败（" + llmFails + " 次）", steps, tokens);
                        return;
                    }
                    continue;
                }
                llmFails = 0;
                steps++;
                tokens += LLM_STEP_TOKENS;
                long llmMs = (System.nanoTime() - t0) / 1_000_000;
                emit(runId, traceId, steps, "PLAN", null, null, null, LLM_STEP_TOKENS, llmMs,
                        intent.thought() == null ? intent.action() : intent.thought());

                switch (intent.action() == null ? "" : intent.action()) {
                    case AgentStepIntent.FINAL_ANSWER -> {
                        emit(runId, traceId, steps, "REFLECT", null, null, null, 0, 0,
                                "任务完成：" + (intent.answer() == null ? "" : intent.answer()));
                        finish(runId, AgentRunStatus.COMPLETED.name(), null, steps, tokens);
                        return;
                    }
                    case AgentStepIntent.REASON -> {
                        history.add("思考:" + safe(intent.thought()));
                        emit(runId, traceId, steps, "REFLECT", null, null, null, 0, 0, safe(intent.thought()));
                    }
                    case AgentStepIntent.TOOL_CALL -> {
                        AgentTool tool = tools.get(intent.tool());
                        if (tool == null) {
                            invalidTool++;
                            history.add("工具不存在:" + safe(intent.tool()));
                            if (invalidTool > INVALID_TOOL_MAX) {
                                finish(runId, AgentRunStatus.TERMINATED.name(),
                                        "连续工具选择错误（" + invalidTool + " 次），已终止", steps, tokens);
                                return;
                            }
                            continue;
                        }
                        Map<String, Object> args = intent.args() == null ? Map.of() : intent.args();
                        String argsHash = hash(args);
                        invalidTool = 0;

                        // ---- 写操作护栏：HITL 挂起 ----
                        if (tool.write()) {
                            repo.hangForApproval(runId, tool.name(), args);
                            emit(runId, traceId, steps, "HITL", tool.name(), argsHash, null, 0, 0,
                                    "写操作待人工审批：" + tool.name());
                            Boolean approved;
                            try {
                                approved = awaitApproval(runId);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                finish(runId, AgentRunStatus.CANCELED.name(), "执行被中断", steps, tokens);
                                return;
                            }
                            if (approved == null) {
                                finish(runId, AgentRunStatus.TERMINATED.name(),
                                        "人工审批超时（10 分钟未响应）", steps, tokens);
                                return;
                            }
                            if (!approved) {
                                history.add("写操作被拒绝:" + tool.name());
                                emit(runId, traceId, steps, "REFLECT", tool.name(), argsHash, null, 0, 0,
                                        "写操作被拒绝，跳过该工具");
                                continue;
                            }
                            repo.resumeFromApproval(runId, AgentRunStatus.RUNNING.name());
                        }

                        // ---- ACT：执行工具 ----
                        emit(runId, traceId, steps, "TOOL_CALL", tool.name(), argsHash, null, 0, 0,
                                "调用工具 " + tool.name());
                        Map<String, Object> result = tool.execute(args);
                        String resultHash = hash(result);
                        emit(runId, traceId, steps, "TOOL_RESULT", tool.name(), argsHash, resultHash, 0, 0,
                                result.toString());

                        // ---- 循环检测（连续 loopThreshold 次相同动作）----
                        String sig = tool.name() + "|" + argsHash;
                        recentSigs.addLast(sig);
                        while (recentSigs.size() > c.loopThreshold()) {
                            recentSigs.removeFirst();
                        }
                        if (recentSigs.size() == c.loopThreshold() && recentSigs.stream().allMatch(sig::equals)) {
                            if (loopWarned) {
                                finish(runId, AgentRunStatus.TERMINATED.name(),
                                        "检测到循环重复动作（" + tool.name() + " 连续" + c.loopThreshold() + "次）", steps, tokens);
                                return;
                            }
                            loopWarned = true;
                            history.add("警告:动作循环，请改变策略或给出最终答案");
                        } else {
                            loopWarned = false;
                        }
                        history.add(tool.name() + " → " + result);
                    }
                    default -> {
                        invalidTool++;
                        history.add("非法决策:" + safe(intent.action()));
                        if (invalidTool > INVALID_TOOL_MAX) {
                            finish(runId, AgentRunStatus.TERMINATED.name(), "模型连续输出非法决策", steps, tokens);
                            return;
                        }
                    }
                }
            }
        } catch (Throwable e) {
            log.error("agent run {} 执行异常", runId, e);
            finish(runId, AgentRunStatus.FAILED.name(), "执行异常: " + e.getMessage(), steps, tokens);
        } finally {
            cancelFlags.remove(runId);
        }
    }

    private Boolean awaitApproval(long runId) throws InterruptedException {
        CompletableFuture<Boolean> f = new CompletableFuture<>();
        approvals.put(runId, f);
        try {
            return f.get(APPROVAL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            approvals.remove(runId);
            return null;
        } catch (InterruptedException ie) {
            approvals.remove(runId);
            throw ie;
        } catch (java.util.concurrent.ExecutionException ee) {
            // 审批 future 仅 complete 完成，理论不触发；防御性兜底视为审批中断
            approvals.remove(runId);
            return null;
        }
    }

    private void finish(long runId, String status, String reason, int steps, int tokens) {
        try {
            repo.updateStatus(runId, status, reason, steps, tokens);
            log.info("agent run {} 结束 status={} reason={} steps={} tokens={}", runId, status, reason, steps, tokens);
        } finally {
            Sinks.Many<AgentTraceEvent> sink = sinks.remove(runId);
            if (sink != null) {
                sink.tryEmitComplete();
            }
        }
    }

    private void emit(long runId, String traceId, int stepNo, String phase, String tool, String argsHash, String resultHash,
                      int llmTokens, long latencyMs, String decision) {
        AgentTraceEvent ev = new AgentTraceEvent(phase, stepNo, tool, argsHash, resultHash, llmTokens, latencyMs,
                decision == null ? null : truncate(decision, 2000), Instant.now());
        try {
            repo.appendStep(runId, new AgentRunRepository.StepRow(
                    ev.phase(), ev.stepNo(), ev.tool(), ev.argsHash(), ev.resultHash(),
                    ev.llmTokens(), ev.latencyMs(), ev.decision()));
        } catch (Exception e) {
            log.warn("agent run {} trace 落库失败: {}", runId, e.getMessage());
        }
        Sinks.Many<AgentTraceEvent> sink = sinks.get(runId);
        if (sink != null) {
            sink.tryEmitNext(ev);
        }
    }

    private Sinks.Many<AgentTraceEvent> sinkOf(long runId) {
        return sinks.computeIfAbsent(runId, k -> Sinks.many().multicast().onBackpressureBuffer());
    }

    /* ---------- Prompt 组装 ---------- */

    private String buildSystemPrompt(AgentConfig c) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是电商客服智能体，通过多步推理与工具调用完成用户任务。\n")
                .append("护栏：最多 ").append(c.maxSteps()).append(" 步；Token 预算 ").append(c.tokenBudget())
                .append("；仅可使用下方列出的工具。\n")
                .append("可用工具（JSON）：");
        List<Map<String, Object>> descs = tools.values().stream()
                .map(t -> Map.<String, Object>of(
                        "name", t.name(), "description", t.description(),
                        "args_schema", t.argsSchema(), "write", t.write()))
                .toList();
        sb.append(toJson(descs));
        sb.append("\n输出规则：只输出一个 JSON 对象，禁止任何多余文本，格式：\n")
                .append("{\"thought\":\"这一步的思考\",\"action\":\"REASON|TOOL_CALL|FINAL_ANSWER\",")
                .append("\"tool\":\"工具名，TOOL_CALL 时必填\",\"args\":{工具参数},\"answer\":\"最终答案，FINAL_ANSWER 时必填\"}\n")
                .append("规则：需要信息先调用工具再作答；工具结果以观察区为准，不得编造；")
                .append("任务完成立即输出 FINAL_ANSWER；写操作（write=true）工具需用户审批，仅在必要且明确时请求调用。");
        return sb.toString();
    }

    private String buildUserPrompt(String task, List<String> history) {
        return "任务：" + task + "\n\n观察历史（时间正序）：\n"
                + (history.isEmpty() ? "（无）" : String.join("\n", history))
                + "\n\n请给出下一步决策。";
    }

    /* ---------- 帮助 ---------- */

    private Map<String, Object> requireRun(long runId, String tenantId, String... allowedStatus) {
        Map<String, Object> d = repo.runDetail(runId);
        if (d == null) {
            throw new BizException(404, "运行不存在: " + runId);
        }
        checkTenant(d, tenantId);
        if (allowedStatus.length > 0 && List.of(allowedStatus).stream().noneMatch(s -> s.equals(d.get("status")))) {
            throw new BizException(409, "运行当前状态为 " + d.get("status") + "，不允许该操作");
        }
        return d;
    }

    private void checkTenant(Map<String, Object> d, String tenantId) {
        if (!tenantId.equals(d.get("tenantId"))) {
            throw new BizException(403, "租户与运行不匹配");
        }
    }

    private static String hash(Object v) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(toJson(v).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", bytes[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(System.identityHashCode(v));
        }
    }

    private static String toJson(Object v) {
        try {
            return JSON.writeValueAsString(v);
        } catch (Exception e) {
            return String.valueOf(v);
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}