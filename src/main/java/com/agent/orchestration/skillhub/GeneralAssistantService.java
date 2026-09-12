package com.agent.orchestration.skillhub;

import com.agent.common.BizException;
import com.agent.data.skillhub.SkillRepository;
import com.agent.model.llm.LlmGateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * L3 编排层：通用 AI 助手（docs/design/architecture/20260905-skill-hub.md §5，W14）。
 * <p>流程：用户提问 → 意图路由（LLM 选技能） → 装配上下文 → 执行（复用 SkillExecutor） →
 * 写操作拦截 → OperationPreview 生成 diff → SSE preview_ready → 等 /confirm → 执行</p>
 */
@Service
public class GeneralAssistantService {

    private static final Logger log = LoggerFactory.getLogger(GeneralAssistantService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final SkillHubService skillHub;
    private final SkillExecutor executor;
    private final OperationPreviewGenerator previewGen;
    private final LlmGateway llm;

    // 会话状态：sessionId -> SessionState
    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();

    public GeneralAssistantService(SkillHubService skillHub, SkillExecutor executor,
                                   OperationPreviewGenerator previewGen, LlmGateway llm) {
        this.skillHub = skillHub;
        this.executor = executor;
        this.previewGen = previewGen;
        this.llm = llm;
    }

    /** 会话状态记录 */
    private record SessionState(String sessionId, String tenantId, String skillName, String status, String previewId) {}

    /** 提问入口：返回 SSE 事件流或直接结果（v1 简化：同步返回，SSE 留演进） */
    public Map<String, Object> ask(String tenantId, String message, String sessionId, String skillHint) {
        String sid = sessionId != null ? sessionId : UUID.randomUUID().toString();

        // 1. 意图路由：选技能
        String skillName = routeSkill(tenantId, message, skillHint);
        if (skillName == null) {
            return Map.of(
                    "sessionId", sid,
                    "status", "NO_SKILL",
                    "answer", "抱歉，我暂时没有合适的技能处理这个请求。可用技能：" +
                            skillHub.listAvailable().stream().map(m -> m.get("displayName")).toList());
        }

        // 2. 获取技能详情
        var skillOpt = skillHub.listAvailable().stream()
                .filter(s -> skillName.equals(s.get("name")))
                .findFirst();
        if (skillOpt.isEmpty()) {
            return Map.of("sessionId", sid, "status", "ERROR", "answer", "技能未发布或不可用：" + skillName);
        }

        Map<String, Object> skillInfo = skillOpt.get();
        String orchestration = (String) skillInfo.get("orchestration");

        // 3. 取真实技能行（含 id，SkillExecutor 据此解析 realic manifest）并执行
        var skillRow = skillHub.findByName(skillName).orElse(null);
        if (skillRow == null) {
            return Map.of("sessionId", sid, "status", "ERROR", "answer", "技能数据缺失：" + skillName);
        }

        try {
            // 3. 执行前检查是否为写操作（需预览）
            var previewOpt = previewGen.generatePreview(tenantId, "GENERAL_ASSISTANT", skillName, Map.of("message", message));

            if (previewOpt.isPresent()) {
                // 写操作：生成预览，等待用户确认
                var preview = previewOpt.get();
                sessions.put(sid, new SessionState(sid, tenantId, skillName, "PENDING_PREVIEW", preview.previewId()));

                return Map.of(
                        "sessionId", sid,
                        "status", "PREVIEW_REQUIRED",
                        "preview", Map.of(
                                "previewId", preview.previewId(),
                                "tool", preview.tool(),
                                "params", preview.params(),
                                "summary", preview.summary(),
                                "impact", preview.impact()
                        ));
            } else {
                // 读操作：直接执行
                String result = executor.execute(skillRow, message);

                // 4. 返回结果
                return Map.of(
                        "sessionId", sid,
                        "status", "COMPLETED",
                        "skill", skillName,
                        "answer", result);
            }
        } catch (Exception e) {
            log.error("助手执行失败: {}", e.getMessage());
            return Map.of("sessionId", sid, "status", "FAILED", "answer", "执行失败：" + e.getMessage());
        }
    }

    /** 确认写操作预览 */
    public Map<String, Object> confirm(String tenantId, String sessionId, String previewId, boolean approved) {
        SessionState state = sessions.get(sessionId);
        if (state == null) {
            throw new BizException(404, "会话不存在: " + sessionId);
        }

        boolean confirmed = previewGen.confirmPreview(previewId, approved);

        if (confirmed) {
            // 确认后，继续执行原技能
            var skillOpt = skillHub.listAvailable().stream()
                    .filter(s -> state.skillName().equals(s.get("name")))
                    .findFirst();
            if (skillOpt.isEmpty()) {
                return Map.of("sessionId", sessionId, "status", "ERROR", "answer", "技能不可用：" + state.skillName());
            }

            var skillRow = skillHub.findByName(state.skillName()).orElse(null);
            if (skillRow == null) {
                return Map.of("sessionId", sessionId, "status", "ERROR", "answer", "技能数据缺失：" + state.skillName());
            }

            try {
                String result = executor.execute(skillRow, "继续执行写操作");
                sessions.remove(sessionId); // 清理会话
                return Map.of(
                        "sessionId", sessionId,
                        "status", "COMPLETED",
                        "skill", state.skillName(),
                        "answer", result);
            } catch (Exception e) {
                log.error("助手执行失败: {}", e.getMessage());
                sessions.remove(sessionId);
                return Map.of("sessionId", sessionId, "status", "FAILED", "answer", "执行失败：" + e.getMessage());
            }
        } else {
            // 拒绝，返回取消
            sessions.remove(sessionId);
            return Map.of(
                    "sessionId", sessionId,
                    "status", "CANCELLED",
                    "answer", "用户取消了写操作"
            );
        }
    }

    /** 获取预览详情 */
    public java.util.Optional<OperationPreviewGenerator.PreviewData> getPreview(String previewId) {
        return previewGen.getPreview(previewId);
    }

    /** 获取可用技能列表 */
    public List<Map<String, Object>> skills() {
        return skillHub.listAvailable();
    }

    /* ---------- 内部：技能路由 ---------- */

    private String routeSkill(String tenantId, String message, String skillHint) {
        if (skillHint != null && !skillHint.isBlank()) {
            // 显式指定技能
            return skillHint;
        }

        // LLM 路由：用所有可用技能的描述让模型选
        List<Map<String, Object>> skills = skillHub.listAvailable();
        if (skills.isEmpty()) {
            return null;
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append("用户输入：").append(message).append("\n\n");
        prompt.append("可用技能：\n");
        for (Map<String, Object> s : skills) {
            prompt.append("- ").append(s.get("name")).append("：")
                    .append(s.get("description")).append("\n");
        }
        prompt.append("\n请判断用户意图属于哪个技能，仅输出技能名（如 trip_advisor），无匹配输出 NONE。");

        try {
            String sys = "你是技能路由器，根据用户输入选择最合适的技能。只输出技能名，不要解释。";
            String selected = llm.generate(sys, prompt.toString());
            if ("NONE".equalsIgnoreCase(selected.trim()) || selected.trim().isBlank()) {
                return null;
            }
            String name = selected.trim();
            // 校验是否在列表中
            return skills.stream().anyMatch(s -> name.equals(s.get("name"))) ? name : null;
        } catch (Exception e) {
            log.warn("LLM 路由失败，回退首个技能: {}", e.getMessage());
            return (String) skills.get(0).get("name");
        }
    }
}