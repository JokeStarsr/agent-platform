package com.agent.tool;

import com.agent.common.BizException;
import com.agent.data.toolinvocation.ToolInvocationRepository;
import com.agent.data.toolinvocation.ToolInvocationRepository.InvocationRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * L5 工具协议层：工具执行引擎（docs/design/architecture/20260901-tool-engine.md §2.3）
 * <p>错误分类：INVALID_ARGS(400, 可重试) / PAYMENT_FORBIDDEN(403) / NOT_FOUND(404) /
 * TIMEOUT(504, 可重试) / INTERNAL(500, 不重试)。写操作幂等由本引擎统一实施。</p>
 */
@Service
public class ToolEngineServiceImpl implements ToolEngineService {

    private static final Logger log = LoggerFactory.getLogger(ToolEngineServiceImpl.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int RESULT_MAX_LEN = 2000;

    private final ToolRegistry registry;
    private final ToolInvocationRepository repo;

    // 单例守护调度线程：超时兜底/并发等待轮询
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "tool-engine-timer");
        t.setDaemon(true);
        return t;
    });

    public ToolEngineServiceImpl(ToolRegistry registry, ToolInvocationRepository repo) {
        this.registry = registry;
        this.repo = repo;
    }

    @Override
    public ToolInvokeResult invoke(String tenantId, String appId, InvokeRequest req) {
        AgentTool tool = registry.resolve(req.tool())
                .orElseThrow(() -> new BizException(404, "工具未注册: " + req.tool()));
        ToolMeta meta = registry.metaOf(req.tool()).orElseThrow();

        if (meta.permission() == ToolPermission.PAYMENT) {
            throw new BizException(403, "支付级工具未开放：本期仅注册不放开");
        }
        Map<String, Object> args = req.args() == null ? Map.of() : req.args();
        validateArgs(meta.parameters(), args);

        String argsHash = hash(args);
        if (!meta.isWrite()) {
            return ToolInvokeResult.ok(executeWithTimeout(tool, args, meta.timeoutMs()));
        }

        // ---- 写操作：幂等键强制 + 三语义 ----
        String key = composeKey(tenantId, appId, req.idempotencyKey());
        Optional<InvocationRow> existing = repo.findByKey(key);
        if (existing.isPresent()) {
            if ("SUCCESS".equals(existing.get().status())) {
                return replayResult(existing.get());
            }
            if ("IN_PROGRESS".equals(existing.get().status())) {
                return awaitConcurrent(key, meta.timeoutMs());
            }
            throw new BizException(409, "该幂等键此前执行失败，请更换键后重试");
        }
        if (!repo.tryCreateInProgress(key, tenantId, req.tool(), argsHash)) {
            // 并发竞争：对方已占键，等其完成
            return awaitConcurrent(key, meta.timeoutMs());
        }
        try {
            Map<String, Object> data = executeWithTimeout(tool, args, meta.timeoutMs());
            repo.markFinished(key, "SUCCESS", truncate(toJson(data), RESULT_MAX_LEN));
            return ToolInvokeResult.ok(data);
        } catch (BizException be) {
            repo.markFinished(key, "FAILED", be.getMessage());
            throw be;
        } catch (Exception e) {
            repo.markFinished(key, "FAILED", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            log.error("工具执行失败: {}", req.tool(), e);
            throw new BizException(500, "工具执行失败: " + e.getMessage());
        }
    }

    @Override
    public List<ToolMeta> listTools() {
        return registry.listTools();
    }

    @Override
    public Optional<ToolMeta> metaOf(String name) {
        return registry.metaOf(name);
    }

    @Override
    public Optional<ToolInvokeResult> replay(String tenantId, String idempotencyKey) {
        return repo.findByKey(composeKey(tenantId, null, idempotencyKey))
                .filter(r -> "SUCCESS".equals(r.status()))
                .map(ToolEngineServiceImpl::replayResult);
    }

    /* ---------- 内部 ---------- */

    /** 幂等键组合：{tenant}:{appId}:{uuid}，防止跨租户撞键 */
    private String composeKey(String tenantId, String appId, String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            throw new BizException(400, "写操作必须携带幂等键 idempotencyKey");
        }
        if (rawKey.length() > 128) {
            throw new BizException(400, "幂等键超长（≤128）");
        }
        String t = tenantId == null ? "default" : tenantId;
        String a = appId == null ? "AGENT" : appId;
        return t + ":" + a + ":" + rawKey;
    }

    /** 参数 Schema 校验（400 INVALID_ARGS，文案回给模型重试） */
    private void validateArgs(String schemaJson, Map<String, Object> args) {
        try {
            ParamSchemaValidator.validate(schemaJson, args);
        } catch (ParamSchemaValidator.ValidationException e) {
            throw new BizException(400, "参数校验失败: " + e.getMessage());
        } catch (Exception e) {
            throw new BizException(400, "参数校验异常: " + e.getMessage());
        }
    }

    /** 超时执行：超过 timeoutMs 抛 TimeoutException */
    private Map<String, Object> executeWithTimeout(AgentTool tool, Map<String, Object> args, long timeoutMs) {
        CompletableFuture<Map<String, Object>> future = CompletableFuture.supplyAsync(() -> tool.execute(args));
        timer.schedule(() -> future.completeExceptionally(new TimeoutException("tool timeout")),
                timeoutMs, TimeUnit.MILLISECONDS);
        try {
            return future.get(timeoutMs + 500, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof TimeoutException te) {
                throw new BizException(504, "工具执行超时: " + tool.name());
            }
            throw new BizException(500, "工具执行失败: " + (e.getCause() == null ? e : e.getCause().getMessage()));
        } catch (TimeoutException e) {
            throw new BizException(504, "工具执行超时: " + tool.name());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new BizException(500, "工具执行被中断");
        } catch (CompletionException ce) {
            throw new BizException(500, "工具执行失败: " + ce.getMessage());
        }
    }

    /** 等待同键并发调用完成（轮询，上限 = 工具超时） */
    private ToolInvokeResult awaitConcurrent(String key, long timeoutMs) {
        long deadline = System.nanoTime() + Duration.ofMillis(timeoutMs + 500).toNanos();
        while (System.nanoTime() < deadline) {
            Optional<InvocationRow> row = repo.findByKey(key);
            if (row.isPresent()) {
                String status = row.get().status();
                if ("SUCCESS".equals(status)) {
                    return replayResult(row.get());
                }
                if ("FAILED".equals(status)) {
                    throw new BizException(500, "同幂等键并发执行失败，请更换键重试");
                }
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new BizException(500, "等待并发执行被中断");
            }
        }
        throw new BizException(504, "等待同键并发执行超时");
    }

    private static ToolInvokeResult replayResult(InvocationRow row) {
        return ToolInvokeResult.replay(readJson(row.resultPayload()));
    }

    private static String hash(Object v) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] b = md.digest(toJson(v).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", b[i]));
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return JSON.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of("_raw", json);
        }
    }

    private static String truncate(String s, int max) {
        return s == null ? null : (s.length() <= max ? s : s.substring(0, max));
    }
}