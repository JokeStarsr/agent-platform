package com.agent.orchestration.workflow;

import com.agent.common.BizException;
import com.agent.common.PageResult;
import com.agent.data.workflow.WorkflowRepository;
import com.agent.data.workflow.WorkflowRepository.InstanceRow;
import com.agent.data.workflow.WorkflowRepository.NodeRow;
import com.agent.model.llm.LlmGateway;
import com.agent.tool.ToolEngineService;
import com.agent.tool.ToolEngineService.InvokeRequest;
import com.agent.tool.ToolMeta;
import com.agent.tool.ToolPermission;
import com.agent.tool.ToolRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * L3 编排层：Workflow 引擎核心（docs/design/architecture/20260831-workflow-engine.md §2）
 * <p>设计要点：
 * <ul>
 *  <li>DB 为唯一权威：每节点提交后整量写 variable 快照，崩溃/重启从节点状态重建（断点恢复）</li>
 *  <li>人工节点挂起 100% 落库（WAITING_APPROVAL），不依赖内存 future——重启不丢，超时升级扫描器接管</li>
 *  <li>v1 运行时支持节点类型 TOOL/LLM/HUMAN/PARALLEL；CONDITION/SUBFLOW 解析校验合法但运行时抛不支持（AC-1/2/3 与商旅主流程均不含）</li>
 * </ul></p>
 */
@Service
public class WorkflowServiceImpl implements WorkflowService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowServiceImpl.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 可重试工具错误码（超时/键冲突）——重试换新幂等键 */
    private static final Set<Integer> RETRYABLE_TOOL_CODES = Set.of(504, 409);
    private static final int TOOL_RETRY_MAX = 1;
    private static final int LLM_RETRY_MAX = 3;                       // LLM 空输出/异常短退避重试（zen 通道抖动，W8 成功率优化）
    private static final String LLM_SYSTEM = com.agent.orchestration.appfactory.PromptCenter.defaultPrompt("workflow-llm-node");
    private static final long DEFAULT_HUMAN_ESCALATE_MS = 30 * 60_000L;
    private static final String REJECTED_REASON = "REJECTED_HUMAN";

    private final WorkflowRepository repo;
    private final WorkflowDefParser parser;
    private final ToolEngineService toolEngine;
    private final ToolRegistry toolRegistry;
    private final LlmGateway llm;

    private final ExecutorService executor = Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r, "workflow-engine");
        t.setDaemon(true);
        return t;
    });
    private final Map<Long, Sinks.Many<WorkflowEvent>> sinks = new ConcurrentHashMap<>();
    private final Set<Long> running = ConcurrentHashMap.newKeySet();

    public WorkflowServiceImpl(WorkflowRepository repo, WorkflowDefParser parser,
                               ToolEngineService toolEngine, ToolRegistry toolRegistry, LlmGateway llm) {
        this.repo = repo;
        this.parser = parser;
        this.toolEngine = toolEngine;
        this.toolRegistry = toolRegistry;
        this.llm = llm;
    }

    /* ---------- 接口实现 ---------- */

    @Override
    public PageResult<Map<String, Object>> listInstances(String tenantId, int page, int size, String status) {
        long total = repo.countInstances(tenantId, status);
        List<Map<String, Object>> items = repo.pageInstances(tenantId, page, size, status).stream()
                .map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("instanceId", r.instanceId());
                    m.put("appId", r.appId());
                    m.put("flowId", r.flowId());
                    m.put("status", r.status());
                    m.put("errorMsg", r.errorMsg());
                    m.put("createdAt", r.createdAt() == null ? null : r.createdAt().toString());
                    m.put("finishedAt", r.finishedAt() == null ? null : r.finishedAt().toString());
                    return m;
                })
                .toList();
        return PageResult.of(page, size, total, items);
    }

    @Override
    public long start(String tenantId, String appId, String flowDefJson, Map<String, Object> input) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new BizException(400, "缺少租户标识 X-Tenant-Id");
        }
        WorkflowGraph graph;
        try {
            graph = parser.parse(flowDefJson);
        } catch (Exception e) {
            throw new BizException(400, "流程定义非法: " + rootMsg(e));
        }
        String traceId = "wf" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
        long instanceId = repo.createInstance(tenantId, appId, graph.flowId(), flowDefJson,
                writeJson(input == null ? Map.of() : input), traceId);
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("input", input == null ? Map.of() : input);
        repo.commitInstance(instanceId, WorkflowStatus.RUNNING.name(), writeJson(vars), "[]", null);
        emit(instanceId, "FLOW_START", null, WorkflowStatus.RUNNING.name(), "流程启动");
        runAsync(instanceId);
        return instanceId;
    }

    @Override
    public Map<String, Object> detail(String tenantId, long instanceId) {
        InstanceRow inst = requireInstance(tenantId, instanceId);
        return repo.instanceDetail(instanceId);
    }

    @Override
    public List<Map<String, Object>> nodeHistory(String tenantId, long instanceId) {
        requireInstance(tenantId, instanceId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (NodeRow r : repo.nodeRuns(instanceId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("nodeRunId", r.nodeRunId());
            m.put("nodeId", r.nodeId());
            m.put("nodeType", r.nodeType());
            m.put("parentNodeId", r.parentNodeId());
            m.put("attempt", r.attempt());
            m.put("status", r.status());
            m.put("input", r.inputSnapshot());
            m.put("output", r.outputSnapshot());
            m.put("idempotencyKey", r.idempotencyKey());
            m.put("errorMsg", r.errorMsg());
            m.put("escalatedAt", r.escalatedAt() == null ? null : r.escalatedAt().toString());
            m.put("finishedAt", r.finishedAt() == null ? null : r.finishedAt().toString());
            out.add(m);
        }
        return out;
    }

    @Override
    public void approve(String tenantId, long instanceId, long nodeRunId, boolean approved, String comment) {
        InstanceRow inst = requireInstance(tenantId, instanceId);
        if (!WorkflowStatus.WAITING_APPROVAL.name().equals(inst.status())) {
            throw new BizException(409, "实例当前状态 " + inst.status() + "，不可审批");
        }
        NodeRow node = repo.findNode(nodeRunId)
                .orElseThrow(() -> new BizException(404, "节点不存在: " + nodeRunId));
        if (node.instanceId() != instanceId || !NodeStatus.WAITING_APPROVAL.name().equals(node.status())) {
            throw new BizException(409, "节点非待审批状态");
        }
        Map<String, Object> vars = readJson(inst.variables());
        if (approved) {
            Map<String, Object> result = Map.of("approved", true, "comment", comment == null ? "" : comment);
            vars.put(node.nodeId(), result);
            repo.finishNode(nodeRunId, NodeStatus.COMPLETED.name(), writeJson(result), null);
            repo.commitInstance(instanceId, WorkflowStatus.RUNNING.name(), writeJson(vars), "[]", null);
            emit(instanceId, "HUMAN_RESULT", node.nodeId(), NodeStatus.COMPLETED.name(), "人工审批通过，流程恢复");
            runAsync(instanceId);
        } else {
            Map<String, Object> result = Map.of("approved", false, "comment", comment == null ? "" : comment);
            vars.put(node.nodeId(), result);
            repo.finishNode(nodeRunId, NodeStatus.FAILED.name(), writeJson(result), REJECTED_REASON);
            repo.commitInstance(instanceId, WorkflowStatus.RUNNING.name(), writeJson(vars), "[]", null);
            // 驳回触发补偿回滚
            List<CompensationItem> comp = readCompensation(inst.compensation());
            comp = compensate(inst.tenantId(), inst.appId(), graphOf(inst.flowDef()), comp);
            repo.finishInstance(instanceId, WorkflowStatus.FAILED.name(), writeCompensation(comp), "人工驳回，已完成写操作已回滚");
            emit(instanceId, "COMPENSATING", node.nodeId(), WorkflowStatus.FAILED.name(), "人工驳回，触发补偿回滚");
            emit(instanceId, "FLOW_END", null, WorkflowStatus.FAILED.name(), "流程 FAILED");
        }
    }

    @Override
    public void cancel(String tenantId, long instanceId) {
        InstanceRow inst = requireInstance(tenantId, instanceId);
        String st = inst.status();
        if (!WorkflowStatus.RUNNING.name().equals(st) && !WorkflowStatus.WAITING_APPROVAL.name().equals(st)) {
            throw new BizException(409, "实例当前状态 " + st + "，不可取消");
        }
        List<CompensationItem> comp = compensate(inst.tenantId(), inst.appId(),
                graphOf(inst.flowDef()), readCompensation(inst.compensation()));
        repo.finishInstance(instanceId, WorkflowStatus.CANCELED.name(), writeCompensation(comp), "用户取消，已完成写操作已回滚");
        emit(instanceId, "FLOW_END", null, WorkflowStatus.CANCELED.name(), "流程已取消");
    }

    @Override
    public void retryNode(String tenantId, long instanceId, long nodeRunId) {
        InstanceRow inst = requireInstance(tenantId, instanceId);
        if (!WorkflowStatus.FAILED.name().equals(inst.status())) {
            throw new BizException(409, "实例当前状态 " + inst.status() + "，仅 FAILED 可重试失败节点");
        }
        NodeRow node = repo.findNode(nodeRunId)
                .orElseThrow(() -> new BizException(404, "节点不存在: " + nodeRunId));
        if (node.instanceId() != instanceId || !NodeStatus.FAILED.name().equals(node.status())) {
            throw new BizException(409, "节点非 FAILED 状态，不可重试");
        }
        if (REJECTED_REASON.equals(node.errorMsg())) {
            throw new BizException(409, "人工驳回的节点不可重试，请重新启动新流程");
        }
        List<NodeRow> attempts = repo.nodeAttempts(instanceId, node.nodeId());
        int nextAttempt = attempts.stream().mapToInt(NodeRow::attempt).max().orElse(0) + 1;
        repo.createNodeRun(instanceId, node.nodeId(), node.nodeType(), node.parentNodeId(), nextAttempt);
        repo.commitInstance(instanceId, WorkflowStatus.RUNNING.name(),
                writeJson(readJson(inst.variables())), "[]", null);
        emit(instanceId, "NODE_START", node.nodeId(), WorkflowStatus.RUNNING.name(), "失败节点重试 attempt=" + nextAttempt);
        runAsync(instanceId);
    }

    @Override
    public Flux<WorkflowEvent> stream(String tenantId, long instanceId) {
        requireInstance(tenantId, instanceId);
        Sinks.Many<WorkflowEvent> sink = sinks.computeIfAbsent(instanceId,
                k -> Sinks.many().multicast().onBackpressureBuffer());
        return sink.asFlux();
    }

    /* ---------- 事件循环（每次恢复从 DB 重算，天然支持断点/重启恢复） ---------- */

    private void runAsync(long instanceId) {
        if (!running.add(instanceId)) {
            return; // 已有执行线程，勿重复推进
        }
        executor.submit(() -> {
            try {
                executeGraph(instanceId);
            } catch (Throwable e) {
                log.error("workflow {} 执行异常", instanceId, e);
                finishTerminal(instanceId, WorkflowStatus.FAILED.name(), "执行异常: " + rootMsg(e));
            } finally {
                running.remove(instanceId);
            }
        });
    }

    /** 从 DB 重建状态推进：读节点行 → 求 ready 集 → 逐节点分派 → 循环直到 PAUSE / 终态 */
    private void executeGraph(long instanceId) {
        InstanceRow inst = repo.findInstance(instanceId)
                .orElseThrow(() -> new BizException(404, "实例不存在: " + instanceId));
        WorkflowGraph graph = graphOf(inst.flowDef());
        Map<String, Object> vars = readJson(inst.variables());
        List<CompensationItem> comp = readCompensation(inst.compensation());

        while (true) {
            Map<String, NodeRow> latest = latestByNode(repo.nodeRuns(instanceId));
            List<String> ready = readyNodes(graph, latest);

            if (ready.isEmpty()) {
                // 终态判定
                NodeRow parked = findParkedHuman(graph, latest);
                if (parked != null) {
                    repo.commitInstance(instanceId, WorkflowStatus.WAITING_APPROVAL.name(), writeJson(vars), "[]", null);
                    emit(instanceId, "HUMAN_WAIT", parked.nodeId(), NodeStatus.WAITING_APPROVAL.name(), "等待人工审批");
                    return; // 挂起，等 approve() 恢复
                }
                NodeRow failed = findFailedNode(latest);
                if (failed != null) {
                    // 并行 REQUIRE_ALL 部分失败 → 补偿已成功写分支（仅并行父节点的写分支）
                    if (failed.parentNodeId() != null
                            && "REQUIRE_ALL".equals(aggregateOf(graph, failed.parentNodeId()))) {
                        comp = compensateParallelSiblings(inst, graph, comp,
                                latest.keySet(), failed.parentNodeId());
                    }
                    String msg = "节点 " + failed.nodeId() + " 失败: "
                            + (failed.errorMsg() == null ? "未知" : failed.errorMsg());
                    repo.finishInstance(instanceId, WorkflowStatus.FAILED.name(), writeCompensation(comp), msg);
                    emit(instanceId, "FLOW_END", null, WorkflowStatus.FAILED.name(), msg);
                    return;
                }
                repo.commitInstance(instanceId, WorkflowStatus.COMPLETED.name(), writeJson(vars), "[]", null);
                emit(instanceId, "FLOW_END", null, WorkflowStatus.COMPLETED.name(), "流程完成");
                return;
            }

            boolean executedAny = false;
            for (String nid : ready) {
                NodeDef node = graph.nodes().get(nid);
                if (done(latest.get(nid)) || parkedStatus(latest.get(nid))) {
                    continue;
                }
                NodeStepResult r = dispatch(inst, graph, node, vars, comp);
                switch (r.kind()) {
                    case PROCESSED -> executedAny = true;
                    case PARKED -> {
                        repo.commitInstance(instanceId, WorkflowStatus.WAITING_APPROVAL.name(),
                                writeJson(vars), "[]", null);
                        emit(instanceId, "HUMAN_WAIT", node.id(), NodeStatus.WAITING_APPROVAL.name(), "等待人工审批");
                        return;
                    }
                    case FAILED -> {
                        // 节点已标记 FAILED；交由下一轮 ready 空 + findFailedNode 统一收尾（含并行补偿）
                    }
                }
            }
            if (!executedAny) {
                // 本轮无实质推进（如仅 PARALLEL 空跑标记后仍需再算），重算 READY 防死循环守卫
                if (latestByNode(repo.nodeRuns(instanceId)).equals(latest)) {
                    break; // 状态未变 → 终止，避免死循环
                }
            }
        }
    }

    /** 单节点分派。返回 PROCESSED / PARKED(人工挂起) / FAILED(不可恢复，含补偿后终态) */
    private NodeStepResult dispatch(InstanceRow inst, WorkflowGraph graph, NodeDef node,
                                    Map<String, Object> vars, List<CompensationItem> comp) {
        long instanceId = inst.instanceId();
        NodeRow row = ensureRunning(instanceId, node);
        emit(instanceId, "NODE_START", node.id(), NodeStatus.RUNNING.name(), "开始执行 " + node.id());

        switch (node.type()) {
            case TOOL -> {
                Map<String, Object> args = resolveArgs(node.args(), vars);
                ToolMeta meta = toolRegistry.metaOf(node.tool()).orElse(null);
                boolean write = meta != null && meta.permission() != ToolPermission.READ;
                String key = write ? "wf-" + instanceId + "-" + row.nodeRunId() + "-" + shortUuid() : null;
                if (write) {
                    repo.bindIdempotencyKey(row.nodeRunId(), key);
                }
                try {
                    Map<String, Object> data = invokeWithRetry(inst.tenantId(), inst.appId(),
                            node.tool(), args, key);
                    if (node.out() != null && !node.out().isBlank()) {
                        vars.put(node.out(), data);
                    }
                    if (write && node.rollbackTool() != null && !node.rollbackTool().isBlank()) {
                        comp.add(new CompensationItem(node.id(), node.tool(), args,
                                node.rollbackTool(), key, null));
                        repo.updateCompensation(instanceId, writeCompensation(comp));
                    }
                    repo.commitInstance(instanceId, WorkflowStatus.RUNNING.name(),
                            writeJson(vars), "[]", null);
                    repo.finishNode(row.nodeRunId(), NodeStatus.COMPLETED.name(), writeJson(data), null);
                    emit(instanceId, "NODE_DONE", node.id(), NodeStatus.COMPLETED.name(), "工具完成 " + node.tool());
                    return NodeStepResult.processed();
                } catch (BizException be) {
                    repo.finishNode(row.nodeRunId(), NodeStatus.FAILED.name(), null, be.getMessage());
                    emit(instanceId, "NODE_DONE", node.id(), NodeStatus.FAILED.name(), "工具失败 " + be.getMessage());
                    return NodeStepResult.failed("节点 " + node.id() + " 失败: " + be.getMessage());
                }
            }
            case LLM -> {
                // zen 免费通道偶发 502/空 content → 短退避重试 + 空内容守卫（避免 Map.of(null) NPE，提升成功率）
                String content = null;
                String lastErr = null;
                for (int attempt = 0; attempt < LLM_RETRY_MAX; attempt++) {
                    try {
                        String prompt = substitute(node.prompt(), vars);
                        String c = llm.generate(LLM_SYSTEM, prompt);
                        if (c == null || c.isBlank()) {
                            lastErr = "LLM 返回空内容（尝试 " + (attempt + 1) + "/" + LLM_RETRY_MAX + "）";
                        } else {
                            content = c;
                            break;
                        }
                    } catch (Exception e) {
                        if (e instanceof InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        lastErr = rootMsg(e);
                    }
                    if (attempt < LLM_RETRY_MAX - 1) {
                        try {
                            Thread.sleep(300L * (attempt + 1));
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
                if (content == null) {
                    repo.finishNode(row.nodeRunId(), NodeStatus.FAILED.name(), null, lastErr);
                    emit(instanceId, "NODE_DONE", node.id(), NodeStatus.FAILED.name(), "LLM 失败 " + lastErr);
                    return NodeStepResult.failed("节点 " + node.id() + " LLM 失败: " + lastErr);
                }
                if (node.out() != null && !node.out().isBlank()) {
                    vars.put(node.out(), Map.of("text", content));
                }
                repo.commitInstance(instanceId, WorkflowStatus.RUNNING.name(), writeJson(vars), "[]", null);
                repo.finishNode(row.nodeRunId(), NodeStatus.COMPLETED.name(),
                        writeJson(Map.of("text", content)), null);
                emit(instanceId, "NODE_DONE", node.id(), NodeStatus.COMPLETED.name(), "LLM 完成");
                return NodeStepResult.processed();
            }
            case HUMAN -> {
                Instant escAt = Instant.now().plus(node.escalateAfterMs() == null
                        ? DEFAULT_HUMAN_ESCALATE_MS : node.escalateAfterMs(), ChronoUnit.MILLIS);
                String content = node.content() == null ? (node.title() == null ? "" : node.title()) : substitute(node.content(), vars);
                repo.hangNodeForApproval(row.nodeRunId(), escAt, content);
                return NodeStepResult.parked();
            }
            case PARALLEL -> {
                // 并行节点本身到位标记（非执行），随后其分支变为 ready
                repo.finishNode(row.nodeRunId(), NodeStatus.COMPLETED.name(), "{}", null);
                emit(instanceId, "NODE_DONE", node.id(), NodeStatus.COMPLETED.name(), "并行节点就绪，分派分支");
                return NodeStepResult.processed();
            }
            default -> {
                String unsupported = "节点类型 v1 运行时暂不支持: " + node.type();
                repo.finishNode(row.nodeRunId(), NodeStatus.FAILED.name(), null, unsupported);
                emit(instanceId, "NODE_DONE", node.id(), NodeStatus.FAILED.name(), unsupported);
                return NodeStepResult.failed(unsupported);
            }
        }
    }

    /** 工具执行：可重试错误码（504/409）带重试换新键；重试耗尽或不可重试 → 抛 BizException 由上层 FAILED */
    private Map<String, Object> invokeWithRetry(String tenantId, String appId, String tool,
                                                Map<String, Object> args, String key) {
        Map<String, Object> lastData = null;
        for (int attempt = 0; attempt <= TOOL_RETRY_MAX; attempt++) {
            try {
                lastData = toolEngine.invoke(tenantId, appId, new InvokeRequest(tool, args, key)).data();
                return lastData;
            } catch (BizException be) {
                if (RETRYABLE_TOOL_CODES.contains(be.getCode()) && attempt < TOOL_RETRY_MAX) {
                    continue;
                }
                throw be;
            }
        }
        return lastData;
    }

    /* ---------- 人工超时升级扫描（Step 4）将另置独立组件，引擎侧仅保证执行线程驻留 ---------- */

    private NodeRow ensureRunning(long instanceId, NodeDef node) {
        NodeRow existing = latestRow(repo.nodeRuns(instanceId), node.id());
        long rowId = existing != null && NodeStatus.PENDING.name().equals(existing.status())
                ? existing.nodeRunId()
                : repo.createNodeRun(instanceId, node.id(), node.type().name(),
                        node.parentNodeId(), existing == null ? 1 : existing.attempt() + 1);
        repo.markNodeRunning(rowId);
        return repo.findNode(rowId).orElseThrow();
    }

    private NodeRow latestRow(List<NodeRow> runs, String nodeId) {
        return runs.stream().filter(r -> nodeId.equals(r.nodeId()))
                .max(java.util.Comparator.comparingInt(NodeRow::attempt)).orElse(null);
    }

    /* ---------- 图与状态帮助 ---------- */

    private WorkflowGraph graphOf(String flowDef) {
        try {
            return parser.parse(flowDef);
        } catch (Exception e) {
            throw new BizException(500, "流程定义解析失败: " + rootMsg(e));
        }
    }

    /** nodeId → 最高 attempt 的节点行 */
    private Map<String, NodeRow> latestByNode(List<NodeRow> runs) {
        Map<String, NodeRow> m = new HashMap<>();
        for (NodeRow r : runs) {
            NodeRow prev = m.get(r.nodeId());
            if (prev == null || r.attempt() > prev.attempt()) {
                m.put(r.nodeId(), r);
            }
        }
        return m;
    }

    private List<String> readyNodes(WorkflowGraph graph, Map<String, NodeRow> latest) {
        List<String> ready = new ArrayList<>();
        for (String nid : graph.nodes().keySet()) {
            NodeRow row = latest.get(nid);
            // FAILED：已定局，不自动重放（等待 retryNode 新建 attempt 后恢复）
            if (done(row) || parkedStatus(row) || failedStatus(row)) {
                continue;
            }
            if (allPredsDone(graph, nid, latest)) {
                ready.add(nid);
            }
        }
        return ready;
    }

    private boolean failedStatus(NodeRow row) {
        return row != null && NodeStatus.FAILED.name().equals(row.status());
    }

    private boolean allPredsDone(WorkflowGraph graph, String nodeId, Map<String, NodeRow> latest) {
        // 真实边前驱
        for (String[] e : graph.edges()) {
            if (e[1].equals(nodeId) && !done(latest.get(e[0]))) {
                return false;
            }
        }
        // PARALLEL 分支的控制器前驱
        for (var entry : graph.parallelBranches().entrySet()) {
            if (entry.getValue().contains(nodeId) && !done(latest.get(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }

    private boolean done(NodeRow row) {
        if (row == null) {
            return false;
        }
        String st = row.status();
        return NodeStatus.COMPLETED.name().equals(st) || NodeStatus.SKIPPED.name().equals(st);
    }

    private boolean parkedStatus(NodeRow row) {
        return row != null && NodeStatus.WAITING_APPROVAL.name().equals(row.status());
    }

    /** 聚合了顶层节点 + 系统隐含终点，终态判定用 */
    private NodeRow findParkedHuman(WorkflowGraph graph, Map<String, NodeRow> latest) {
        for (var e : latest.values()) {
            if (NodeStatus.WAITING_APPROVAL.name().equals(e.status())) {
                return e;
            }
        }
        return null;
    }

    private NodeRow findFailedNode(Map<String, NodeRow> latest) {
        for (var e : latest.values()) {
            if (NodeStatus.FAILED.name().equals(e.status())) {
                return e;
            }
        }
        return null;
    }

    private String aggregateOf(WorkflowGraph graph, String parentNodeId) {
        if (parentNodeId == null) {
            return "REQUIRE_ALL";
        }
        NodeDef par = graph.nodes().get(parentNodeId);
        return par == null || par.aggregate() == null ? "REQUIRE_ALL" : par.aggregate();
    }

    private List<CompensationItem> compensateParallelSiblings(InstanceRow inst, WorkflowGraph graph,
                                                              List<CompensationItem> comp, Set<String> doneNodes,
                                                              String parentNodeId) {
        // 补偿该并行节点下、已成功(COMPLETED)且登记的写分支
        List<CompensationItem> updated = new ArrayList<>();
        for (CompensationItem item : comp) {
            if (item.compensatedAt() != null || !parentNodeId.equals(parentOf(graph, item.nodeId()))) {
                updated.add(item);
                continue;
            }
            updated.add(compensateOne(inst.tenantId(), inst.appId(), item));
        }
        // 同步压缩 comp 引用数组（调用方会持久化 updated 外部）
        return updated;
    }

    private String parentOf(WorkflowGraph graph, String nodeId) {
        NodeDef n = graph.nodes().get(nodeId);
        return n == null ? null : n.parentNodeId();
    }

    /** 逆序补偿全部待回滚写节点 */
    private List<CompensationItem> compensate(String tenantId, String appId, WorkflowGraph graph,
                                              List<CompensationItem> comp) {
        List<CompensationItem> updated = new ArrayList<>(comp);
        for (int i = comp.size() - 1; i >= 0; i--) {
            CompensationItem item = comp.get(i);
            if (item.compensatedAt() != null) {
                continue;
            }
            updated.set(i, compensateOne(tenantId, appId, item));
        }
        return updated;
    }

    private CompensationItem compensateOne(String tenantId, String appId, CompensationItem item) {
        if (item.rollbackTool() == null || item.rollbackTool().isBlank()) {
            return item; // 无回滚钩子，仅留补偿标记（审计人工兜底）
        }
        try {
            toolEngine.invoke(tenantId, appId,
                    new InvokeRequest(item.rollbackTool(), item.args(), item.idempotencyKey()));
            return new CompensationItem(item.nodeId(), item.tool(), item.args(), item.rollbackTool(),
                    item.idempotencyKey(), Instant.now().toString());
        } catch (BizException be) {
            log.warn("补偿回滚失败 {} ({}): {}", item.tool(), item.idempotencyKey(), be.getMessage());
            return item; // 保留待补偿标记，人工兜底
        }
    }

    private void finishTerminal(long instanceId, String status, String errorMsg) {
        // 保留已有补偿清单，避免被覆盖为 NULL
        String comp = repo.findInstance(instanceId).map(InstanceRow::compensation).orElse("[]");
        repo.finishInstance(instanceId, status, comp, errorMsg);
        emit(instanceId, "FLOW_END", null, status, errorMsg);
    }

    private void emit(long instanceId, String phase, String nodeId, String status, String message) {
        WorkflowEvent ev = new WorkflowEvent(phase, instanceId, nodeId, status, message, Instant.now());
        Sinks.Many<WorkflowEvent> sink = sinks.get(instanceId);
        if (sink != null) {
            sink.tryEmitNext(ev);
        }
    }

    /** 供人工超时升级扫描器推送事件（同包，SSE 汇入） */
    void publishToSink(long instanceId, WorkflowEvent ev) {
        Sinks.Many<WorkflowEvent> sink = sinks.get(instanceId);
        if (sink != null) {
            sink.tryEmitNext(ev);
        }
    }

    private InstanceRow requireInstance(String tenantId, long instanceId) {
        InstanceRow inst = repo.findInstance(instanceId)
                .orElseThrow(() -> new BizException(404, "实例不存在: " + instanceId));
        if (!tenantId.equals(inst.tenantId())) {
            throw new BizException(403, "租户与实例不匹配");
        }
        return inst;
    }

    /* ---------- 变量/参数解析 ---------- */

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveArgs(Map<String, Object> args, Map<String, Object> vars) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (args == null) {
            return out;
        }
        for (var e : args.entrySet()) {
            Object v = e.getValue();
            if (v instanceof String s && s.startsWith("$.")) {
                out.put(e.getKey(), resolvePath(vars, s.substring(2)));
            } else if (v instanceof Map) {
                out.put(e.getKey(), resolveArgs((Map<String, Object>) v, vars));
            } else {
                out.put(e.getKey(), v);
            }
        }
        return out;
    }

    private Object resolvePath(Map<String, Object> root, String path) {
        Map<String, Object> cur = root;
        String[] parts = path.split("\\.");
        for (int i = 0; i < parts.length - 1; i++) {
            Object nxt = cur.get(parts[i]);
            if (!(nxt instanceof Map)) {
                return null;
            }
            cur = (Map<String, Object>) nxt;
        }
        return cur.get(parts[parts.length - 1]);
    }

    private String substitute(String template, Map<String, Object> vars) {
        if (template == null) {
            return "";
        }
        String out = template;
        for (var e : flatten(vars).entrySet()) {
            out = out.replace("${" + e.getKey() + "}", String.valueOf(e.getValue()));
        }
        return out;
    }

    private Map<String, Object> flatten(Map<String, Object> root) {
        Map<String, Object> flat = new HashMap<>();
        flattenInto("", root, flat);
        return flat;
    }

    @SuppressWarnings("unchecked")
    private void flattenInto(String prefix, Map<String, Object> m, Map<String, Object> flat) {
        for (var e : m.entrySet()) {
            String key = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            if (e.getValue() instanceof Map) {
                flattenInto(key, (Map<String, Object>) e.getValue(), flat);
            } else {
                flat.put(key, e.getValue());
            }
        }
    }

    /* ---------- JSON / 其他 ---------- */

    private static String rootMsg(Throwable e) {
        Throwable c = e;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        return c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage();
    }

    private static String shortUuid() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static String writeJson(Object v) {
        try {
            return JSON.writeValueAsString(v);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    private static Map<String, Object> readJson(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return JSON.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private static List<CompensationItem> readCompensation(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return JSON.readValue(json, new TypeReference<List<CompensationItem>>() {
            });
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private static String writeCompensation(List<CompensationItem> comp) {
        return writeJson(comp);
    }

    /** 单节点执行结果 */
    private record NodeStepResult(Kind kind, String message) {
        enum Kind { PROCESSED, PARKED, FAILED }

        static NodeStepResult processed() {
            return new NodeStepResult(Kind.PROCESSED, null);
        }

        static NodeStepResult parked() {
            return new NodeStepResult(Kind.PARKED, null);
        }

        static NodeStepResult failed(String msg) {
            return new NodeStepResult(Kind.FAILED, msg);
        }
    }
}
