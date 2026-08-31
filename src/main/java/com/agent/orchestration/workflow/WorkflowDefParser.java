package com.agent.orchestration.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 流程定义 JSON 解析器（docs/design/architecture/20260831-workflow-engine.md §2.1）
 * 职责：解析 JSON → 展平嵌套节点 → 构建完整图（含 CONDITION/PARALLEL 虚拟边）→ 拓扑排序 + 环检测 → 校验出边合法性
 */
@Component
public class WorkflowDefParser {

    private static final ObjectMapper M = new ObjectMapper();

    /** 解析流程定义 JSON，非法定义抛 IllegalStateException（启动 fail-fast） */
    @SuppressWarnings("unchecked")
    public WorkflowGraph parse(String jsonDef) {
        try {
            Map<String, Object> root = M.readValue(jsonDef, Map.class);
            long defaultTimeoutMs = root.containsKey("timeoutMs")
                    ? ((Number) root.get("timeoutMs")).longValue() : 600_000L;
            String flowId = (String) root.get("flowId");
            List<Map<String, Object>> rawNodes = (List<Map<String, Object>>) root.get("nodes");
            if (rawNodes == null || rawNodes.isEmpty()) {
                throw new IllegalStateException("流程定义无节点");
            }

            // 1. 展平嵌套节点（PARALLEL 分支递归展平）
            Map<String, NodeDef> flatNodes = new LinkedHashMap<>();
            Map<String, List<String>> parallelBranches = new HashMap<>();
            Map<String, List<ConditionBranch>> conditionBranches = new HashMap<>();
            for (Map<String, Object> rn : rawNodes) {
                flattenNode(rn, null, flatNodes, parallelBranches, conditionBranches);
            }

            // 2. 真实边（edges 字段）
            List<List<String>> rawEdges = (List<List<String>>) root.getOrDefault("edges", List.of());
            List<String[]> edges = rawEdges.stream().map(e -> new String[]{e.get(0), e.get(1)}).toList();

            // 3. 边合法性校验（端点存在性 + CONDITION 出边禁止 + 出边数限制）
            validateEdges(flatNodes, edges, conditionBranches, parallelBranches);

            // 4. 构建含虚拟边的验证邻接表（Kahn's 拓扑排序）
            Map<String, List<String>> validAdj = buildValidationAdjacency(flatNodes, edges, conditionBranches, parallelBranches);

            // 5. 拓扑排序 + 环检测
            List<String> order = kahnOrder(flatNodes.keySet(), validAdj);

            String escalationUrl = (String) root.get("escalationUrl");
            return new WorkflowGraph(flowId, defaultTimeoutMs, flatNodes, edges, conditionBranches,
                    parallelBranches, order, escalationUrl);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("流程定义 JSON 解析失败", e);
        }
    }

    // ─── 展平 ──────────────────────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void flattenNode(Map<String, Object> rn, String parentNodeId,
                             Map<String, NodeDef> flatNodes,
                             Map<String, List<String>> parallelBranches,
                             Map<String, List<ConditionBranch>> conditionBranches) {
        String id = (String) rn.get("id");
        NodeType type = NodeType.valueOf((String) rn.get("type"));
        switch (type) {
            case PARALLEL -> {
                List<Map<String, Object>> rawBranches = (List<Map<String, Object>>) rn.get("branches");
                String aggregate = (String) rn.getOrDefault("aggregate", "REQUIRE_ALL");
                long timeoutMs = rn.containsKey("timeoutMs") ? ((Number) rn.get("timeoutMs")).longValue() : 0L;
                List<String> branchIds = new ArrayList<>();
                List<NodeDef> branchDefs = new ArrayList<>();
                for (Map<String, Object> br : rawBranches) {
                    flattenNode(br, id, flatNodes, parallelBranches, conditionBranches);
                    String bid = (String) br.get("id");
                    branchIds.add(bid);
                    branchDefs.add(flatNodes.get(bid));
                }
                parallelBranches.put(id, branchIds);
                flatNodes.put(id, new NodeDef(id, type, null, null, null, null, null,
                        timeoutMs > 0 ? timeoutMs : null,
                        branchDefs, aggregate, null, null, parentNodeId, null, null));
            }
            case CONDITION -> {
                List<Map<String, Object>> rawBranches = (List<Map<String, Object>>) rn.get("branches");
                List<ConditionBranch> branches = rawBranches.stream()
                        .map(b -> new ConditionBranch((String) b.get("id"), (String) b.get("expr"), (String) b.get("next")))
                        .toList();
                conditionBranches.put(id, branches);
                flatNodes.put(id, new NodeDef(id, type, null, null, null, null, null,
                        null, null, null, branches, null, parentNodeId, null, null));
            }
            case HUMAN -> {
                String title = (String) rn.get("title");
                String content = (String) rn.get("content");
                Long escalateAfterMs = rn.containsKey("escalateAfterMs")
                        ? ((Number) rn.get("escalateAfterMs")).longValue() : 30 * 60_000L; // 默认 30 分钟
                flatNodes.put(id, new NodeDef(id, type, null, null, null, null, null,
                        escalateAfterMs, null, null, null, null, parentNodeId, title, content));
            }
            default -> { // TOOL, LLM, SUBFLOW
                Long escalateAfterMs = rn.containsKey("escalateAfterMs")
                        ? ((Number) rn.get("escalateAfterMs")).longValue() : null;
                flatNodes.put(id, new NodeDef(id, type,
                        (String) rn.get("tool"), (Map<String, Object>) rn.get("args"),
                        (String) rn.get("out"),
                        (String) rn.get("rollbackTool"),
                        (String) rn.get("prompt"),
                        escalateAfterMs,
                        null, null, null,
                        (String) rn.get("flowRef"),
                        parentNodeId, null, null));
            }
        }
    }

    // ─── 校验 ──────────────────────────────────────────────────────────────────────────────────

    private void validateEdges(Map<String, NodeDef> flatNodes, List<String[]> edges,
                               Map<String, List<ConditionBranch>> conditionBranches,
                               Map<String, List<String>> parallelBranches) {
        // 端点存在性
        for (String[] e : edges) {
            if (!flatNodes.containsKey(e[0])) throw new IllegalStateException("边引用不存在的节点: " + e[0]);
            if (!flatNodes.containsKey(e[1])) throw new IllegalStateException("边引用不存在的节点: " + e[1]);
        }
        // CONDITION 禁止在 edges 中出边
        for (String[] e : edges) {
            if (flatNodes.get(e[0]).type() == NodeType.CONDITION) {
                throw new IllegalStateException("CONDITION 节点 " + e[0] + " 不允许在 edges 中定义出边（应使用 branches）");
            }
        }
        // 出边数限制
        Map<String, Integer> outDegree = new HashMap<>();
        for (String[] e : edges) {
            outDegree.merge(e[0], 1, Integer::sum);
        }
        for (var entry : flatNodes.entrySet()) {
            String nid = entry.getKey();
            NodeType type = entry.getValue().type();
            int out = outDegree.getOrDefault(nid, 0);
            if (type == NodeType.PARALLEL) {
                if (out != 1) {
                    throw new IllegalStateException("PARALLEL 节点 " + nid + " 必须恰好有一条出边到聚合后继节点，实际 " + out);
                }
            } else if (type != NodeType.CONDITION) {
                if (out > 1) {
                    throw new IllegalStateException("节点 " + nid + " 有多个出边（" + out + "）");
                }
                // out == 0 允许（终态节点）
            }
        }
    }

    // ─── 含虚拟边的验证邻接表 ──────────────────────────────────────────────────────────────────

    private Map<String, List<String>> buildValidationAdjacency(
            Map<String, NodeDef> flatNodes, List<String[]> edges,
            Map<String, List<ConditionBranch>> conditionBranches,
            Map<String, List<String>> parallelBranches) {
        Map<String, List<String>> adj = new HashMap<>();
        flatNodes.keySet().forEach(k -> adj.put(k, new ArrayList<>()));
        // 真实边
        for (String[] e : edges) {
            adj.computeIfAbsent(e[0], k -> new ArrayList<>()).add(e[1]);
        }
        // 虚拟边：CONDITION → branches[].next
        for (var entry : conditionBranches.entrySet()) {
            String condId = entry.getKey();
            List<String> targets = adj.computeIfAbsent(condId, k -> new ArrayList<>());
            for (ConditionBranch cb : entry.getValue()) {
                targets.add(cb.next());
            }
        }
        // 虚拟边：PARALLEL → 展平后的分支节点 id
        for (var entry : parallelBranches.entrySet()) {
            String parId = entry.getKey();
            List<String> targets = adj.computeIfAbsent(parId, k -> new ArrayList<>());
            for (String bid : entry.getValue()) {
                targets.add(bid);
            }
        }
        return adj;
    }

    // ─── Kahn's 拓扑排序（环检测） ──────────────────────────────────────────────────────────────

    private List<String> kahnOrder(java.util.Set<String> allNodes, Map<String, List<String>> adj) {
        Map<String, Integer> inDeg = new HashMap<>();
        allNodes.forEach(n -> inDeg.put(n, 0));
        for (var entry : adj.entrySet()) {
            for (String target : entry.getValue()) {
                inDeg.merge(target, 1, Integer::sum);
            }
        }
        Deque<String> queue = new ArrayDeque<>();
        inDeg.forEach((n, d) -> { if (d == 0) queue.add(n); });
        List<String> order = new ArrayList<>();
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            order.add(cur);
            for (String next : adj.getOrDefault(cur, List.of())) {
                if (inDeg.merge(next, -1, Integer::sum) == 0) {
                    queue.add(next);
                }
            }
        }
        if (order.size() != allNodes.size()) {
            int diff = allNodes.size() - order.size();
            throw new IllegalStateException("DAG 存在环（差 " + diff + " 个节点），无法拓扑排序");
        }
        return order;
    }
}
