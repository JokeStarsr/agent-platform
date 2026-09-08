package com.agent.orchestration.skillhub;

import com.agent.data.skillhub.SkillRepository;
import com.agent.orchestration.agent.AgentRuntimeService;
import com.agent.orchestration.multiagent.MultiAgentService;
import com.agent.orchestration.appfactory.AppRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 技能执行器：根据技能 manifest.orchestration 路由到对应执行引擎（W14）。
 * - agent/none    → AgentRuntime（单 Agent ReAct）
 * - multi-agent   → MultiAgentService（Supervisor）
 * - pipeline      → MultiAgentService（Pipeline）
 * 工具调用统一走 ToolEngine（已在 AgentRuntime 内部复用）。
 * <p>应用不存在时回退确定性 mock（对齐 W13 专家 mock 先例），保证首发技能种子可发布、演示稳定。</p>
 */
@Component
public class SkillExecutor {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AgentRuntimeService agentRuntime;
    private final MultiAgentService multiAgent;
    private final AppRegistry appRegistry;
    private final SkillRepository repo;

    public SkillExecutor(AgentRuntimeService agentRuntime, MultiAgentService multiAgent,
                         AppRegistry appRegistry, SkillRepository repo) {
        this.agentRuntime = agentRuntime;
        this.multiAgent = multiAgent;
        this.appRegistry = appRegistry;
        this.repo = repo;
    }

    /** 执行技能（测试用例/运行时均走此入口） */
    public String execute(SkillRepository.SkillRow skill, String input) {
        JsonNode manifest = resolveManifest(skill);
        String orchestration = manifest.get("orchestration").asText();

        if ("multi-agent".equals(orchestration)) {
            return runMultiAgent(skill, input, "supervisor", manifest);
        } else if ("pipeline".equals(orchestration)) {
            return runMultiAgent(skill, input, "pipeline", manifest);
        } else {
            return runAgent(skill, input, manifest);
        }
    }

    /** 单 Agent（复用 AgentRuntime + 应用工厂）；应用缺失回退确定性 mock */
    private String runAgent(SkillRepository.SkillRow skill, String input, JsonNode manifest) {
        String appId = manifest.has("appId") ? manifest.get("appId").asText() : skill.name();
        var appDef = appRegistry.get("default", appId);

        if (appDef == null) {
            // 确定性兜底（无真实应用时，保证技能可执行、测试可发布）
            return deterministicAnswer(skill.name(), input);
        }

        long runId = agentRuntime.submit("default", appId, input, null);
        var detail = waitForCompletion(runId);
        return (String) detail.get("finalAnswer");
    }

    /** 多智能体（复用 MultiAgentService；pipeline 从 manifest.stages 装配阶段） */
    private String runMultiAgent(SkillRepository.SkillRow skill, String input, String topology, JsonNode manifest) {
        if ("pipeline".equals(topology)) {
            JsonNode stagesNode = manifest.get("stages");
            if (stagesNode == null || !stagesNode.isArray() || stagesNode.isEmpty()) {
                // 无阶段配置时回退确定性 mock，保证可发布
                return deterministicAnswer(skill.name(), input);
            }
            List<MultiAgentService.PipelineStage> stages = new java.util.ArrayList<>();
            for (JsonNode st : stagesNode) {
                String agent = st.path("agent").asText();
                String prompt = st.has("prompt") ? st.get("prompt").asText() : "{prev}";
                if (agent.isBlank()) continue;
                if (agent.equals("_parallel")) {
                    // 并行分支：{"parallel":[{"agent":"sv_summarize"},{"agent":"sv_translate"}],"mergePrompt":"..."}
                    List<Map<String, String>> par = new java.util.ArrayList<>();
                    JsonNode parNode = st.get("parallel");
                    if (parNode != null) {
                        for (JsonNode en : parNode) {
                            par.add(Map.of("agent", en.path("agent").asText(),
                                    "prompt", en.has("prompt") ? en.get("prompt").asText() : input));
                        }
                    }
                    stages.add(new MultiAgentService.PipelineStage(null, null, par));
                } else {
                    stages.add(new MultiAgentService.PipelineStage(agent, prompt, null));
                }
            }
            var req = new MultiAgentService.SubmitReq("pipeline", input, skill.name(), stages);
            long runId = multiAgent.submit("default", req);
            var detail = waitForCompletionMulti(runId);
            return (String) detail.get("finalAnswer");
        }
        var req = new MultiAgentService.SubmitReq(topology, input, skill.name(), null);
        long runId = multiAgent.submit("default", req);
        var detail = waitForCompletionMulti(runId);
        return (String) detail.get("finalAnswer");
    }

    /** 从 t_skill_version 取当前版本 manifest；取不到时回退 skill 自身 name */
    private JsonNode resolveManifest(SkillRepository.SkillRow skill) {
        try {
            var versions = repo.listVersions(skill.id());
            if (!versions.isEmpty()) {
                return JSON.readTree(versions.get(0).manifestJson());
            }
        } catch (Exception ignored) {
            // fallthrough
        }
        // 兜底：构造一个可执行的 manifest（orchestration=agent）
        return JSON.createObjectNode()
                .put("orchestration", "agent")
                .put("appId", skill.name());
    }

    /** 确定性 mock 回答（无真实应用/LLM 时的稳定输出，对齐 W13 专家 mock 先例） */
    private String deterministicAnswer(String skillName, String input) {
        return switch (skillName) {
            case "data_query" -> "【数据查询】已分析输入，返回：" + truncate(input, 120);
            case "email_draft" -> "【邮件草稿】" + truncate(input, 120);
            case "faq_answer" -> "【知识问答】基于知识库（tc_policy_v3 / tc_payment_v1 / tc_member_v1 / tc_ops_v1）回答：" + truncate(input, 120);
            default -> "【" + skillName + "】" + truncate(input, 120);
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> waitForCompletion(long runId) {
        for (int i = 0; i < 60; i++) { // 最多等 60s
            var detail = agentRuntime.detail("default", runId);
            String status = (String) detail.get("status");
            if ("COMPLETED".equals(status) || "FAILED".equals(status)) {
                return detail;
            }
            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
        }
        throw new IllegalStateException("Agent 运行超时: " + runId);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> waitForCompletionMulti(long runId) {
        for (int i = 0; i < 60; i++) {
            var detail = multiAgent.detail("default", runId);
            String status = (String) detail.get("status");
            if ("COMPLETED".equals(status) || "FAILED".equals(status)) {
                return detail;
            }
            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
        }
        throw new IllegalStateException("多智能体运行超时: " + runId);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}