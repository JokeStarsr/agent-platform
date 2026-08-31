package com.agent.tool;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * L5 工具协议层：工具注册中心 v1（docs/design/architecture/20260901-tool-engine.md §2.1）
 * Spring 组件扫描收集全部 AgentTool bean → 内存注册表。
 * 启动 fail-fast：工具名冲突 / 非法命名（非小写下划线）→ 抛异常阻止启动。
 */
@Component
public class ToolRegistry {

    private final Map<String, AgentTool> instances = new HashMap<>();
    private final Map<String, ToolMeta> metas = new HashMap<>();

    public ToolRegistry(List<AgentTool> tools) {
        for (AgentTool t : tools) {
            String name = t.name();
            if (name == null || !name.matches("[a-z_][a-z0-9_]*")) {
                throw new IllegalStateException("工具名非法（须小写下划线）: " + name);
            }
            if (instances.containsKey(name)) {
                throw new IllegalStateException("工具名冲突，注册失败: " + name + "（" + instances.get(name).getClass().getName() + " vs " + t.getClass().getName() + "）");
            }
            instances.put(name, t);
            metas.put(name, new ToolMeta(name, t.description(), t.parameters(), t.permission(), t.timeoutMs()));
        }
    }

    public Optional<AgentTool> resolve(String name) {
        return Optional.ofNullable(instances.get(name));
    }

    public Optional<ToolMeta> metaOf(String name) {
        return Optional.ofNullable(metas.get(name));
    }

    public List<ToolMeta> listTools() {
        return List.copyOf(metas.values());
    }
}