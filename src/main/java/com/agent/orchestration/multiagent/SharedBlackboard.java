package com.agent.orchestration.multiagent;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * L3 编排层：多智能体共享黑板（docs/design/architecture/20260905-multi-agent.md §5）。
 * <p>根 run 内跨子 Agent 共享的任务状态/中间结果（内存 v1）。
 * 键约定：out_{phase}（Pipeline 阶段输出）/ sub_{agent}（子任务结果）/ final（汇总）。
 * 读写均需租户匹配（黑板键按 rootRunId 隔离）。</p>
 */
@Component
public class SharedBlackboard {

    private final Map<Long, Map<String, Object>> boards = new ConcurrentHashMap<>();

    public void write(String tenantId, long rootRunId, String key, Object value) {
        boards.computeIfAbsent(rootRunId, k -> new LinkedHashMap<>()).put(key, value);
    }

    public Object read(String tenantId, long rootRunId, String key) {
        Map<String, Object> b = boards.get(rootRunId);
        return b == null ? null : b.get(key);
    }

    public Map<String, Object> read(String tenantId, long rootRunId) {
        return boards.getOrDefault(rootRunId, Map.of());
    }

    public void clear(String tenantId, long rootRunId) {
        boards.remove(rootRunId);
    }
}