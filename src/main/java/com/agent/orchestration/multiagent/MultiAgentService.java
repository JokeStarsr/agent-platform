package com.agent.orchestration.multiagent;

import com.agent.common.BizException;
import com.agent.common.PageResult;
import com.agent.data.multiagent.MultiAgentRunRepository;
import com.agent.data.multiagent.MultiAgentRunRepository.RunRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * L3 编排层：多智能体编排（docs/design/architecture/20260905-multi-agent.md，W13）
 * <p>Supervisor：Planner 分解（v1 内置 plan 模板，确定性闭环）→ 专家子任务（内置专家，
 * 不动用 LLM，保证回归稳定）→ 汇总拼接 final。Pipeline：阶段串行（支持 parallel 分支），
 * 每阶段输出进黑板。BudgetGuard：总预算 40% planner/final、60% 均分子任务，熔断超预算路径。
 * 子 Agent 复用真实 AgentRuntime 的执行接入留 W14（Skill Hub 消费专家应用）；v1 专家为 mock。</p>
 */
@Service
public class MultiAgentService {

    private static final Logger log = LoggerFactory.getLogger(MultiAgentService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int DEFAULT_BUDGET = 10_000;
    private static final int EXPERT_STEPS_TOKENS = 500;

    private final MultiAgentRunRepository repo;
    private final SharedBlackboard blackboard;

    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "multi-agent");
        t.setDaemon(true);
        return t;
    });

    /** 内置专家 appId → 结果模板（v1 mock，真实专家应用接 W14） */
    private static final Map<String, java.util.function.Function<String, String>> EXPERTS = Map.of(
            "sv_policy", task -> "【政策】" + detectCity(task) + "出差住宿标准：经济型 ≤400元/晚，交通标准：高铁二等座/经济舱。",
            "sv_compare", task -> "【比价】可选航班 CA101 ¥1200 / MU202 ¥980（" + detectCity(task) + "）；酒店 3 星 ¥350、4 星 ¥520。",
            "sv_final", task -> "【方案】基于以上信息，" + detectCity(task) + "出差建议：住宿选 4 星（¥520 达标），往返选 MU202（¥980 更省）。",
            "sv_summarize", input -> "【摘要】" + truncate(input, 120),
            "sv_translate", input -> "【译文】" + truncate(input, 120));

    public MultiAgentService(MultiAgentRunRepository repo, SharedBlackboard blackboard) {
        this.repo = repo;
        this.blackboard = blackboard;
    }

    public record SubmitReq(String topology, String task, String appId, List<PipelineStage> stages) {
    }

    public record PipelineStage(String agent, String prompt, List<Map<String, String>> parallel) {
    }

    public record PlanStep(String agent, String task, List<String> deps) {
    }

    /** 提交多智能体任务 → rootRunId */
    public long submit(String tenantId, SubmitReq req) {
        String topology = req.topology() == null ? "supervisor" : req.topology();
        if (!"supervisor".equals(topology) && !"pipeline".equals(topology)) {
            throw new BizException(400, "topology 仅支持 supervisor / pipeline");
        }
        int budget = DEFAULT_BUDGET;
        RunRow row = repo.create(new RunRow(0, tenantId, topology, req.task(), req.appId(),
                toJson(req.stages()), null, "PLANNING", null, 0, budget, null, null));
        long rootRunId = row.id();
        executor.submit(() -> run(topology, tenantId, rootRunId, req, budget));
        return rootRunId;
    }

    public Map<String, Object> detail(String tenantId, long id) {
        RunRow row = requireRow(tenantId, id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", row.id());
        m.put("topology", row.topology());
        m.put("task", row.task());
        m.put("appId", row.appId());
        m.put("status", row.status());
        m.put("plan", parseJson(row.planJson()));
        m.put("stages", parseJson(row.stagesJson()));
        m.put("finalAnswer", row.finalAnswer());
        m.put("totalToken", row.totalToken());
        m.put("budgetLimit", row.budgetLimit());
        m.put("board", blackboard.read(tenantId, row.id()));
        m.put("createdAt", String.valueOf(row.createdAt()));
        return m;
    }

    public PageResult<Map<String, Object>> list(String tenantId, int page, int size, String status) {
        int p = Math.max(1, page);
        int s = Math.min(50, Math.max(1, size));
        long total = repo.count(tenantId);
        List<RunRow> rows = repo.list(tenantId, status, s, (p - 1) * s);
        List<Map<String, Object>> items = rows.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.id());
            m.put("topology", r.topology());
            m.put("task", truncate(r.task(), 60));
            m.put("status", r.status());
            m.put("totalToken", r.totalToken());
            m.put("createdAt", String.valueOf(r.createdAt()));
            return m;
        }).toList();
        return new PageResult<>(p, s, total, items);
    }

    public void cancel(String tenantId, long id) {
        RunRow row = requireRow(tenantId, id);
        if ("COMPLETED".equals(row.status()) || "FAILED".equals(row.status())
                || "CANCELLED".equals(row.status())) {
            throw new BizException(409, "运行已结束，无法取消");
        }
        repo.updateStatus(id, "CANCELLED");
        blackboard.clear(tenantId, id);
    }

    /* ---------- 执行 ---------- */

    private void run(String topology, String tenantId, long rootRunId, SubmitReq req, int budget) {
        try {
            ExecOut out;
            if ("pipeline".equals(topology)) {
                out = runPipeline(tenantId, rootRunId, req, budget);
            } else {
                out = runSupervisor(tenantId, rootRunId, req, budget);
            }
            repo.updateFinal(rootRunId, out.finalAnswer(), out.used());
        } catch (BizException be) {
            log.warn("多智能体运行失败 id={}: {}", rootRunId, be.getMessage());
            repo.updateStatus(rootRunId, "FAILED");
            blackboard.clear(tenantId, rootRunId);
        } catch (Exception e) {
            log.error("多智能体运行异常 id={}", rootRunId, e);
            repo.updateStatus(rootRunId, "FAILED");
            blackboard.clear(tenantId, rootRunId);
        }
    }

    private record ExecOut(String finalAnswer, int used) {
    }

    private ExecOut runSupervisor(String tenantId, long rootRunId, SubmitReq req, int budget) {
        // 1. Planner：内置 plan 模板（确定性闭环；真实 LLM 分解留 W14）
        List<PlanStep> plan = defaultPlan(req.task());
        repo.updatePlan(rootRunId, toJson(plan));

        // 2. 预算分配：planner+final 40%，子任务 60% 均分
        int subBudget = (int) (budget * 0.6 / Math.max(1, plan.size()));

        // 3. 按 deps 批次执行（v1 简化：全部并行；deps 仅记录语义）
        Map<String, String> results = new LinkedHashMap<>();
        int used = 0;
        for (PlanStep step : plan) {
            if (used > subBudget) {
                throw new BizException(400, "预算超限（子任务熔断）");
            }
            String out = runExpert(step.agent(), step.task());
            results.put(step.agent(), out);
            used += EXPERT_STEPS_TOKENS;
            blackboard.write(tenantId, rootRunId, "sub_" + step.agent(), out);
        }

        // 4. Final 汇总（mock 专家拼接）
        StringBuilder sb = new StringBuilder();
        for (PlanStep step : plan) {
            if (results.containsKey(step.agent())) {
                sb.append(results.get(step.agent())).append("\n");
            }
        }
        return new ExecOut(sb.toString().trim(), used);
    }

    private ExecOut runPipeline(String tenantId, long rootRunId, SubmitReq req, int budget) {
        if (req.stages() == null || req.stages().isEmpty()) {
            throw new BizException(400, "pipeline 必须提供 stages");
        }
        String prev = req.task();
        int used = 0;
        int idx = 0;
        for (PipelineStage stage : req.stages()) {
            // parallel 分支：一阶段内多专家各自处理 prev 后合并
            if (stage.parallel() != null && !stage.parallel().isEmpty()) {
                String pv = prev; // lambda 需捕获不变量
                StringBuilder merged = new StringBuilder();
                for (Map<String, String> p : stage.parallel()) {
                    String agent = p.get("agent");
                    String prompt = p.getOrDefault("prompt", pv);
                    merged.append(EXPERTS.getOrDefault(agent, a -> "【" + a + "】" + truncate(pv, 80)))
                            .append(truncate(prompt, 80)).append("\n");
                    used += EXPERT_STEPS_TOKENS;
                }
                prev = merged.toString().trim();
            } else if (stage.agent() != null) {
                String prompt = stage.prompt() == null ? prev : stage.prompt().replace("{prev}", prev);
                prev = runExpert(stage.agent(), prompt);
                used += EXPERT_STEPS_TOKENS;
            }
            blackboard.write(tenantId, rootRunId, "out_" + idx, prev);
            idx++;
            if (used > budget) {
                throw new BizException(400, "预算超限（pipeline 熔断）");
            }
        }
        return new ExecOut(prev, used);
    }

    private String runExpert(String agent, String input) {
        var fn = EXPERTS.get(agent);
        if (fn == null) {
            return "【" + agent + "】" + truncate(input, 100);
        }
        return fn.apply(input);
    }

    /** v1 内置 plan：政策 → 比价 → 汇总（确定性，回归稳定） */
    private List<PlanStep> defaultPlan(String task) {
        String city = detectCity(task);
        return List.of(
                new PlanStep("sv_policy", "查询" + city + "出差政策标准", null),
                new PlanStep("sv_compare", "比对" + city + "航班与酒店方案", List.of("sv_policy")),
                new PlanStep("sv_final", "汇总" + city + "出差方案", List.of("sv_compare")));
    }

    private static String detectCity(String task) {
        if (task == null) {
            return "目标城市";
        }
        for (String city : new String[]{"北京", "上海", "广州", "深圳", "成都", "杭州", "武汉", "西安", "南京", "重庆"}) {
            if (task.contains(city)) {
                return city;
            }
        }
        return "目标城市";
    }

    private RunRow requireRow(String tenantId, long id) {
        return repo.findById(id)
                .filter(r -> tenantId.equals(r.tenantId()))
                .orElseThrow(() -> new BizException(404, "多智能体运行不存在: id=" + id));
    }

    private Object parseJson(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return JSON.readValue(s, Object.class);
        } catch (Exception e) {
            return s;
        }
    }

    private String toJson(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            return "[]";
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}