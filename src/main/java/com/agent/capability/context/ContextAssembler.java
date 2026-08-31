package com.agent.capability.context;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 上下文组装器（docs/design/architecture/20260901-memory-context.md §2.3/§2.4）
 * <p>按预算分片分配，超限按级联顺序压缩：工具白名单 → 历史摘要化 → RAG 降 Top-K/截断 → 记忆降 Top-M。
 * System 保底不入压缩。返回 prompt + usage（每片 token 与压缩动作，供成本看板/审计）。</p>
 */
@Component
public class ContextAssembler {

    /** 分片占比（人工审定） */
    private static final double W_SYSTEM = 0.10;
    private static final double W_RAG = 0.35;
    private static final double W_HISTORY = 0.25;
    private static final double W_MEMORY = 0.15;
    private static final double W_TOOL = 0.15;
    private static final int DEFAULT_BUDGET = 16_384;

    public ContextBundle assemble(String system, String task, List<String> history, List<String> ragHits,
                                  List<String> memories, List<String> tools) {
        return assemble(system, task, history, ragHits, memories, tools, DEFAULT_BUDGET);
    }

    public ContextBundle assemble(String system, String task, List<String> history, List<String> ragHits,
                                  List<String> memories, List<String> tools, int budget) {
        int b = budget > 0 ? budget : DEFAULT_BUDGET;
        int capSystem = (int) (b * W_SYSTEM);
        int capRag = (int) (b * W_RAG);
        int capHistory = (int) (b * W_HISTORY);
        int capMemory = (int) (b * W_MEMORY);
        int capTool = (int) (b * W_TOOL);
        List<String> actions = new ArrayList<>();

        // System 保底：固定装配，不入压缩
        String systemBlock = system == null ? "" : system;

        // 工具：白名单截取（顺序保留，超 budget 截断）
        List<String> toolLines = fitLines(tools, capTool, "工具超预算，白名单截断", actions, true);

        // 历史：保留最近 → 更早摘要化
        List<String> histLines = fitLines(history, capHistory, "更早历史已摘要省略", actions, false);
        if (histLines.stream().anyMatch(l -> l.startsWith("["))) {
            actions.add("history_summary");
        }

        // RAG：降 Top-K（按传入顺序即相关度）
        List<String> ragLines = fitLines(ragHits, capRag, "RAG 降 Top-K 并截断", actions, true);

        // 记忆：降 Top-M
        List<String> memLines = fitLines(memories, capMemory, "记忆降 Top-M", actions, true);

        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("system", est(systemBlock));
        usage.put("rag", est(join(ragLines)));
        usage.put("history", est(join(histLines)));
        usage.put("memory", est(join(memLines)));
        usage.put("tool", est(join(toolLines)));
        int total = (Integer) usage.get("system") + (Integer) usage.get("rag") + (Integer) usage.get("history")
                + (Integer) usage.get("memory") + (Integer) usage.get("tool");
        usage.put("total", total);
        usage.put("budget", b);
        usage.put("withinBudget", total <= b);

        Map<String, Object> breakdown = new LinkedHashMap<>();
        breakdown.put("actions", actions);
        if (!actions.isEmpty()) {
            breakdown.put("compressed", true);
        }

        StringBuilder user = new StringBuilder("任务：" + (task == null ? "" : task)).append("\n");
        if (!ragLines.isEmpty()) {
            user.append("\n[知识库上下文]\n").append(join(ragLines)).append("\n");
        }
        if (!memLines.isEmpty()) {
            user.append("\n[用户长期记忆]\n").append(join(memLines)).append("\n");
        }
        if (!histLines.isEmpty()) {
            user.append("\n[会话历史]\n").append(join(histLines)).append("\n");
        }
        if (!toolLines.isEmpty()) {
            user.append("\n[可用工具]\n").append(join(toolLines)).append("\n");
        }
        return new ContextBundle(systemBlock, user.toString(), usage, actions);
    }

    /** 从尾部（最近）向头部保留，使其总估算 token ≤ cap；放不下时前置压缩标记行 */
    private List<String> fitLines(List<String> items, int cap, String overflowMarker,
                                  List<String> actions, boolean recordAction) {
        List<String> kept = new ArrayList<>();
        if (items == null || items.isEmpty()) {
            return kept;
        }
        int budget = 0;
        boolean overflow = false;
        for (int i = items.size() - 1; i >= 0; i--) {
            int t = est(items.get(i));
            if (budget + t > cap) {
                overflow = true;
                continue;
            }
            budget += t;
            kept.add(0, items.get(i)); // 保持原序
        }
        if (overflow) {
            kept.add(0, "[" + overflowMarker + "]");
            if (recordAction) {
                actions.add(overflowMarker);
            }
        }
        return kept;
    }

    private static int est(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        return (s.length() + 2) / 3;
    }

    private static String join(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        return String.join("\n", lines);
    }

    /** 组装结果：system + user + usage + 压缩动作 */
    public record ContextBundle(String system, String user, Map<String, Object> usage, List<String> actions) {
    }
}
