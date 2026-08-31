package com.agent.tool;

/**
 * L5 工具协议层：工具元数据（注册表快照，Prompt 组装与 API 展示用）
 */
public record ToolMeta(String name, String description, String parameters, ToolPermission permission, long timeoutMs) {

    public boolean isWrite() {
        return permission != ToolPermission.READ;
    }
}