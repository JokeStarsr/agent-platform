package com.agent.tool;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * L5 工具协议层：工具注册中心（docs/design/architecture/20260901-tool-engine.md §2.1）
 * <p>构造时收集全部 {@code AgentTool} beans → 内存注册表（fail-fast 校验）；
 * W11 起支持动态注册/注销（工具市场自助注册发布 → register，下架 → unregister），
 * 与 ToolCatalogRepository 协作（目录是管理面，本机构造执行面掩码）。</p>
 */
@Component
public class ToolRegistry {

    private final Map<String, AgentTool> instances = new HashMap<>();
    private final Map<String, ToolMeta> metas = new HashMap<>();

    public ToolRegistry(List<AgentTool> tools) {
        for (AgentTool t : tools) {
            register(t);
        }
    }

    /** 动态注册（工具市场发布时调用）；重名覆盖并告警（registry 以最新注册为准） */
    public synchronized void register(AgentTool tool) {
        String name = tool.name();
        if (name == null || !name.matches("[a-z_][a-z0-9_]*")) {
            throw new IllegalArgumentException("工具名非法（须小写下划线）: " + name);
        }
        if (instances.containsKey(name)) {
            org.slf4j.LoggerFactory.getLogger(ToolRegistry.class)
                    .warn("工具名重复注册，覆盖旧实现: {}", name);
        }
        instances.put(name, tool);
        metas.put(name, new ToolMeta(name, tool.description(), tool.parameters(), tool.permission(), tool.timeoutMs()));
    }

    /** 动态注销（工具市场下架时调用）；返回是否真的移除了一个注册 */
    public synchronized boolean unregister(String name) {
        boolean removed = instances.remove(name) != null;
        metas.remove(name);
        return removed;
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