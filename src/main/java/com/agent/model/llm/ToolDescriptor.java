package com.agent.model.llm;

/**
 * L6 模型服务层：工具描述（Agent Runtime 决策用）
 * <p>v1 工具执行在编排层手动进行（见 LlmGateway.generateStructured 说明），
 * 模型仅根据描述选择工具，由 AgentRuntimeServiceImpl 执行。</p>
 *
 * @param name        工具名（模型输出时精确匹配）
 * @param description 用途说明（决定模型选择准确率）
 * @param argsSchema  参数 JSON Schema 字符串（模型生成参数的依据）
 * @param write       是否写操作（写操作需人工审批，HITL）
 */
public record ToolDescriptor(String name, String description, String argsSchema, boolean write) {
}