package com.agent.orchestration.workflow;

import com.agent.common.BizException;
import com.agent.data.workflow.WorkflowRepository;
import com.agent.data.workflow.WorkflowRepository.InstanceRow;
import com.agent.data.workflow.WorkflowRepository.NodeRow;
import com.agent.model.llm.LlmGateway;
import com.agent.tool.AgentTool;
import com.agent.tool.ToolEngineService;
import com.agent.tool.ToolEngineService.InvokeRequest;
import com.agent.tool.ToolEngineService.ToolInvokeResult;
import com.agent.tool.ToolPermission;
import com.agent.tool.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Workflow 引擎核心单测（docs/design/architecture/20260831-workflow-engine.md §7 验收用例 AC-1/2/3）
 * 使用内存 fake repository（模拟 DB 行为），使异步事件循环的多轮读取与实际落库语义一致。
 */
class WorkflowServiceImplTest {

    private static final String TENANT = "default";
    private static final String APP = "TR_DEMO";

    private WorkflowRepository repo;
    private ToolEngineService toolEngine;
    private LlmGateway llm;
    private WorkflowServiceImpl cut;
    private final List<InvokeRequest> toolCalls = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        repo = new FakeWorkflowRepository();
        toolEngine = mock(ToolEngineService.class);
        llm = mock(LlmGateway.class);
        when(toolEngine.invoke(eq(TENANT), anyString(), any(InvokeRequest.class))).thenAnswer(inv -> {
            InvokeRequest req = inv.getArgument(2);
            toolCalls.add(req);
            switch (req.tool()) {
                case "policy_query" -> {
                    return ToolInvokeResult.ok(Map.of("allowance", "5000"));
                }
                case "book_order" -> {
                    return ToolInvokeResult.ok(Map.of("orderId", "O-01"));
                }
                case "send_coupon" -> {
                    if (!SEND_COUPON_OK.get()) {
                        throw new BizException(500, "send_coupon 执行失败");
                    }
                    return ToolInvokeResult.ok(Map.of("sent", true));
                }
                case "notify_user" -> {
                    return ToolInvokeResult.ok(Map.of("notified", true));
                }
                case "cancel_order" -> {
                    return ToolInvokeResult.ok(Map.of("cancelled", true));
                }
                default -> {
                    return ToolInvokeResult.ok(Map.of("result", req.tool()));
                }
            }
        });
        ToolRegistry registry = new ToolRegistry(List.of(
                new StubTool("policy_query", ToolPermission.READ),
                new StubTool("book_order", ToolPermission.WRITE),
                new StubTool("cancel_order", ToolPermission.WRITE),
                new StubTool("notify_user", ToolPermission.WRITE),
                new StubTool("send_coupon", ToolPermission.WRITE)));
        cut = new WorkflowServiceImpl(repo, new WorkflowDefParser(), toolEngine, registry, llm);
    }

    @AfterEach
    void tearDown() {
        for (long id : ((FakeWorkflowRepository) repo).ids()) {
            try {
                cut.cancel(TENANT, id);
            } catch (Exception ignored) {
            }
        }
    }

    /* ---------- AC-1 挂起-恢复 ---------- */

    @Test
    void ac1_正常完成_线性流程_工具链执行完成() throws Exception {
        long id = cut.start(TENANT, APP, linear("appr"), Map.of("city", "北京"));
        waitUntil(() -> "COMPLETED".equals(status(id)) || "FAILED".equals(status(id)));
        assertEquals("COMPLETED", status(id));
        assertTrue(toolCalls.stream().anyMatch(r -> "policy_query".equals(r.tool())));
        assertTrue(toolCalls.stream().anyMatch(r -> "notify_user".equals(r.tool())));
    }

    @Test
    void ac1b_人工节点挂起_approve恢复后继续完成() throws Exception {
        long id = cut.start(TENANT, APP, linearWithHuman(), Map.of("city", "北京"));
        // 跑到人工节点挂起
        waitUntil(() -> "WAITING_APPROVAL".equals(status(id)));
        NodeRow human = repo.nodeRuns(id).stream()
                .filter(r -> "HUMAN".equals(r.nodeType()))
                .max(java.util.Comparator.comparingInt(NodeRow::attempt)).orElseThrow();
        assertTrue(toolCalls.stream().noneMatch(r -> "notify_user".equals(r.tool())), "挂起时不应执行后续节点");

        cut.approve(TENANT, id, human.nodeRunId(), true, "同意");
        waitUntil(() -> "COMPLETED".equals(status(id)) || "FAILED".equals(status(id)));
        assertEquals("COMPLETED", status(id), "审批通过后应继续执行到完成");
        assertTrue(toolCalls.stream().anyMatch(r -> "notify_user".equals(r.tool())));
    }

    /* ---------- AC-2 驳回回退 ---------- */

    @Test
    void ac2_人工驳回_前置写节点被补偿回滚_实例FAILED() throws Exception {
        long id = cut.start(TENANT, APP, rejectFlow(), Map.of());
        waitUntil(() -> "WAITING_APPROVAL".equals(status(id)));
        NodeRow human = repo.nodeRuns(id).stream()
                .filter(r -> "HUMAN".equals(r.nodeType())).max(java.util.Comparator.comparingInt(NodeRow::attempt))
                .orElseThrow();
        // book_order 已成功执行一次
        InvokeRequest book = toolCalls.stream().filter(r -> "book_order".equals(r.tool())).findFirst().orElseThrow();
        String bookKey = book.idempotencyKey();
        assertNotNull(bookKey, "写操作必须携带幂等键");

        cut.approve(TENANT, id, human.nodeRunId(), false, "驳回");
        waitUntil(() -> "FAILED".equals(status(id)));
        assertEquals("FAILED", status(id));
        // 回滚在同一线程内完成；同步校验补偿调用，key 复用原幂等键
        InvokeRequest cancel = toolCalls.stream().filter(r -> "cancel_order".equals(r.tool())).findFirst()
                .orElseThrow(() -> new AssertionError("驳回后应调用 cancel_order 补偿"));
        assertEquals(bookKey, cancel.idempotencyKey(), "补偿必须复用原写操作的幂等键（防重复补偿）");
    }

    /* ---------- AC-3 并行部分失败 ---------- */

    @Test
    void ac3_并行分支部分失败_成功分支被补偿_可retryNode续跑() throws Exception {
        SEND_COUPON_OK.set(false); // send_coupon 分支失败
        long id = cut.start(TENANT, APP, parallelFlow(), Map.of());
        // 等并行分支阶段结束 → FAILED
        waitUntil(() -> "FAILED".equals(status(id)));
        // 成功分支 book_order 已执行并被补偿
        InvokeRequest book = toolCalls.stream().filter(r -> "book_order".equals(r.tool())).findFirst().orElseThrow();
        InvokeRequest cancel = toolCalls.stream().filter(r -> "cancel_order".equals(r.tool())).findFirst()
                .orElseThrow(() -> new AssertionError("REQUIRE_ALL 分支失败应补偿已成功写分支"));
        assertEquals(book.idempotencyKey(), cancel.idempotencyKey(), "并行成功分支补偿复用原幂等键");

        // 失败分支 retryNode 续跑（send_coupon 改为成功）
        SEND_COUPON_OK.set(true);
        NodeRow failed = repo.nodeRuns(id).stream()
                .filter(r -> "n1b".equals(r.nodeId())).max(java.util.Comparator.comparingInt(NodeRow::attempt))
                .orElseThrow();
        cut.retryNode(TENANT, id, failed.nodeRunId());
        waitUntil(() -> "COMPLETED".equals(status(id)) || "FAILED".equals(status(id)));
        assertEquals("COMPLETED", status(id), "retryNode 成功后流程应走完");
    }

    /* ---------- 流程定义 ---------- */

    /** 无人工节点：n1 工具 → n2 工具 */
    private static String linear(String post) {
        return "{\"flowId\":\"ac1\",\"nodes\":["
                + "{\"id\":\"n1\",\"type\":\"TOOL\",\"tool\":\"policy_query\",\"args\":{\"city\":\"$.input.city\"},\"out\":\"policy\"},"
                + "{\"id\":\"n2\",\"type\":\"TOOL\",\"tool\":\"notify_user\",\"args\":{\"allowance\":\"$.policy\"},\"out\":\"done\"}],"
                + "\"edges\":[[\"n1\",\"n2\"]]}";
    }

    /** n1 工具 → 人工 → n3 工具（AC-1b） */
    private static String linearWithHuman() {
        return "{\"flowId\":\"ac1h\",\"nodes\":["
                + "{\"id\":\"n1\",\"type\":\"TOOL\",\"tool\":\"policy_query\",\"args\":{\"city\":\"$.input.city\"},\"out\":\"policy\"},"
                + "{\"id\":\"n2\",\"type\":\"HUMAN\",\"title\":\"方案确认\",\"content\":\"政策 ${policy}\",\"escalateAfterMs\":1800000},"
                + "{\"id\":\"n3\",\"type\":\"TOOL\",\"tool\":\"notify_user\",\"args\":{\"allowance\":\"$.policy\"},\"out\":\"done\"}],"
                + "\"edges\":[[\"n1\",\"n2\"],[\"n2\",\"n3\"]]}";
    }

    /** n1 写工具(book_order) → 人工（AC-2 驳回补偿） */
    private static String rejectFlow() {
        return "{\"flowId\":\"ac2\",\"nodes\":["
                + "{\"id\":\"n1\",\"type\":\"TOOL\",\"tool\":\"book_order\",\"args\":{\"plan\":\"$\"},\"out\":\"order\",\"rollbackTool\":\"cancel_order\"},"
                + "{\"id\":\"n2\",\"type\":\"HUMAN\",\"title\":\"确认下单\",\"content\":\"order ${order}\",\"escalateAfterMs\":1800000}],"
                + "\"edges\":[[\"n1\",\"n2\"]]}";
    }

    /** PARALLEL 两写分支（AC-3 并行部分失败） */
    private static String parallelFlow() {
        return "{\"flowId\":\"ac3\",\"nodes\":["
                + "{\"id\":\"n1\",\"type\":\"PARALLEL\",\"aggregate\":\"REQUIRE_ALL\",\"branches\":["
                + "   {\"id\":\"n1a\",\"type\":\"TOOL\",\"tool\":\"book_order\",\"args\":{\"b\":\"a\"},\"out\":\"oa\",\"rollbackTool\":\"cancel_order\"},"
                + "   {\"id\":\"n1b\",\"type\":\"TOOL\",\"tool\":\"send_coupon\",\"args\":{\"b\":\"b\"},\"out\":\"ob\"}"
                + "]},"
                + "{\"id\":\"n2\",\"type\":\"TOOL\",\"tool\":\"notify_user\",\"args\":{\"x\":\"1\"},\"out\":\"nn\"}],"
                + "\"edges\":[[\"n1\",\"n2\"],[\"n1a\",\"n2\"],[\"n1b\",\"n2\"]]}";
    }

    /* ---------- 帮助 ---------- */

    private static volatile java.util.concurrent.atomic.AtomicBoolean SEND_COUPON_OK =
            new java.util.concurrent.atomic.AtomicBoolean(true);

    private String status(long id) {
        return repo.findInstance(id).map(InstanceRow::status).orElse("MISSING");
    }

    private interface Pred {
        boolean test();
    }

    private static void waitUntil(Pred p) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (p.test()) {
                return;
            }
            Thread.sleep(50);
        }
        fail("等待超时，状态未达预期");
    }

    /** 内存 fake repository：模拟真实 DB 的读写语义（状态可达，供异步事件循环多轮读取） */
    static class StubTool implements AgentTool {
        private final String name;
        private final ToolPermission permission;

        StubTool(String name, ToolPermission permission) {
            this.name = name;
            this.permission = permission;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return name;
        }

        @Override
        public ToolPermission permission() {
            return permission;
        }

        @Override
        public Map<String, Object> execute(Map<String, Object> args) {
            return Map.of("result", name);
        }
    }

    /** 内存 fake：覆盖引擎用到的仓库方法，模拟 DB 状态推移 */
    static class FakeWorkflowRepository extends WorkflowRepository {
        private final Map<Long, InstanceRow> instances = new java.util.concurrent.ConcurrentHashMap<>();
        private final Map<Long, List<NodeRow>> nodeRuns = new java.util.concurrent.ConcurrentHashMap<>();
        private long seqInstance;
        private long seqNode;

        FakeWorkflowRepository() {
            super(null);
        }

        List<Long> ids() {
            return new ArrayList<>(instances.keySet());
        }

        @Override
        public synchronized long createInstance(String tenantId, String appId, String flowId, String flowDef,
                                                String input, String traceId) {
            long id = ++seqInstance;
            instances.put(id, new InstanceRow(id, tenantId, appId, flowId, flowDef, "CREATED", input, "{}", "[]", "[]",
                    traceId, null, Instant.now(), null, null));
            return id;
        }

        @Override
        public Optional<InstanceRow> findInstance(long instanceId) {
            return Optional.ofNullable(instances.get(instanceId));
        }

        @Override
        public synchronized void commitInstance(long instanceId, String status, String variables,
                                                String currentNodeIds, String errorMsg) {
            InstanceRow r = instances.get(instanceId);
            if (r == null) {
                return;
            }
            instances.put(instanceId, new InstanceRow(r.instanceId(), r.tenantId(), r.appId(), r.flowId(), r.flowDef(),
                    status, r.input(), variables, r.compensation(),
                    currentNodeIds, r.traceId(), errorMsg, r.createdAt(), r.startedAt(), null));
        }

        @Override
        public synchronized void finishInstance(long instanceId, String status, String compensation, String errorMsg) {
            InstanceRow r = instances.get(instanceId);
            if (r == null) {
                return;
            }
            instances.put(instanceId, new InstanceRow(r.instanceId(), r.tenantId(), r.appId(), r.flowId(), r.flowDef(),
                    status, r.input(), r.variables(), compensation == null ? r.compensation() : compensation,
                    r.currentNodeIds(), r.traceId(), errorMsg, r.createdAt(), r.startedAt(), Instant.now()));
        }

        @Override
        public synchronized void updateInstanceStatus(long instanceId, String status, String errorMsg) {
            commitInstance(instanceId, status, instances.get(instanceId).variables(), "[]", errorMsg);
        }

        @Override
        public void updateCompensation(long instanceId, String compensationJson) {
            InstanceRow r = instances.get(instanceId);
            if (r == null) {
                return;
            }
            instances.put(instanceId, new InstanceRow(r.instanceId(), r.tenantId(), r.appId(), r.flowId(), r.flowDef(),
                    r.status(), r.input(), r.variables(), compensationJson,
                    r.currentNodeIds(), r.traceId(), r.errorMsg(), r.createdAt(), r.startedAt(), r.finishedAt()));
        }

        @Override
        public synchronized long createNodeRun(long instanceId, String nodeId, String nodeType,
                                               String parentNodeId, int attempt) {
            long rid = ++seqNode;
            List<NodeRow> list = nodeRuns.computeIfAbsent(instanceId, k -> new java.util.concurrent.CopyOnWriteArrayList<>());
            list.add(new NodeRow(rid, instanceId, nodeId, nodeType, parentNodeId, attempt, "PENDING",
                    null, null, null, null, null, null, Instant.now(), null));
            return rid;
        }

        @Override
        public Optional<NodeRow> findNode(long nodeRunId) {
            for (var list : nodeRuns.values()) {
                for (NodeRow r : list) {
                    if (r.nodeRunId() == nodeRunId) {
                        return Optional.of(r);
                    }
                }
            }
            return Optional.empty();
        }

        @Override
        public void markNodeRunning(long nodeRunId) {
            updateNode(nodeRunId, "RUNNING", null);
        }

        @Override
        public void hangNodeForApproval(long nodeRunId, Instant escalationAt, String inputSnapshot) {
            updateNode(nodeRunId, "WAITING_APPROVAL", null);
        }

        @Override
        public void finishNode(long nodeRunId, String status, String outputSnapshot, String errorMsg) {
            updateNode(nodeRunId, status, outputSnapshot);
            for (var list : nodeRuns.values()) {
                for (int i = 0; i < list.size(); i++) {
                    NodeRow r = list.get(i);
                    if (r.nodeRunId() == nodeRunId) {
                        list.set(i, new NodeRow(r.nodeRunId(), r.instanceId(), r.nodeId(), r.nodeType(), r.parentNodeId(),
                                r.attempt(), status, r.inputSnapshot(), outputSnapshot, r.idempotencyKey(),
                                r.escalationAt(), r.escalatedAt(), errorMsg, r.startedAt(),
                                status.equals("COMPLETED") || status.equals("FAILED") ? Instant.now() : null));
                    }
                }
            }
        }

        @Override
        public void bindIdempotencyKey(long nodeRunId, String idempotencyKey) {
            for (var list : nodeRuns.values()) {
                for (int i = 0; i < list.size(); i++) {
                    NodeRow r = list.get(i);
                    if (r.nodeRunId() == nodeRunId) {
                        list.set(i, new NodeRow(r.nodeRunId(), r.instanceId(), r.nodeId(), r.nodeType(), r.parentNodeId(),
                                r.attempt(), r.status(), r.inputSnapshot(), r.outputSnapshot(), idempotencyKey,
                                r.escalationAt(), r.escalatedAt(), r.errorMsg(), r.startedAt(), r.finishedAt()));
                    }
                }
            }
        }

        @Override
        public List<NodeRow> nodeAttempts(long instanceId, String nodeId) {
            List<NodeRow> out = new ArrayList<>();
            for (NodeRow r : nodeRuns.getOrDefault(instanceId, List.of())) {
                if (nodeId.equals(r.nodeId())) {
                    out.add(r);
                }
            }
            return out;
        }

        @Override
        public List<NodeRow> nodeRuns(long instanceId) {
            return new ArrayList<>(nodeRuns.getOrDefault(instanceId, List.of()));
        }

        private void updateNode(long nodeRunId, String status, String outputSnapshot) {
            for (var list : nodeRuns.values()) {
                for (int i = 0; i < list.size(); i++) {
                    NodeRow r = list.get(i);
                    if (r.nodeRunId() == nodeRunId) {
                        NodeRow nr = new NodeRow(r.nodeRunId(), r.instanceId(), r.nodeId(), r.nodeType(), r.parentNodeId(),
                                r.attempt(), status, r.inputSnapshot(), outputSnapshot != null ? outputSnapshot : r.outputSnapshot(),
                                r.idempotencyKey(), r.escalationAt(), r.escalatedAt(), r.errorMsg(), r.startedAt(), r.finishedAt());
                        list.set(i, nr);
                    }
                }
            }
        }
    }
}
