package com.agent.orchestration.agent;

import java.util.Map;

/**
 * L3 编排层：工具 SPI（v1 种子工具为函数式，W5 工具注册中心/MCP 落地后兼容迁移）
 * 写操作工具的 execute 需经 HITL 审批后才会被 Runtime 调用。
 */
public interface AgentTool {

    /** 工具名（模型输出时精确匹配） */
    String name();

    /** 用途说明（决定模型工具选择准确率） */
    String description();

    /** 参数 JSON Schema 字符串，如 {"type":"object","properties":{...}} */
    String argsSchema();

    /** 是否写操作（true 时触发人工审批 HITL） */
    boolean write();

    /** 执行并返回观察结果（仅工具可读字段，不进 LLM 的脱敏在工具内完成） */
    Map<String, Object> execute(Map<String, Object> args);
}