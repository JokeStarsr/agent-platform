package com.agent.tool;

import java.util.Map;

/**
 * L5 工具协议层：工具 SPI（docs/design/architecture/20260901-tool-engine.md §2.1）
 * <p>实现类以 Spring 组件注册（@Component），由 ToolRegistry 启动扫描收集并 fail-fast 校验。
 * 幂等/权限/校验由引擎统一执行，工具实现不得自行绕过。</p>
 */
public interface AgentTool {

    /** 工具名：小写下划线，全局唯一（冲突启动即失败） */
    String name();

    /** 用途说明（决定模型工具选择准确率，W5 排期要求人工撰写） */
    String description();

    /** 参数 JSON Schema 字符串（org.everit.json.schema 校验），默认空对象 */
    default String parameters() {
        return "{\"type\":\"object\",\"properties\":{}}";
    }

    /** 权限等级 */
    ToolPermission permission();

    /** 单次执行超时（毫秒），默认 30s */
    default long timeoutMs() {
        return 30_000;
    }

    /** 执行并返回观察结果（引擎负责校验/幂等/超时包装） */
    Map<String, Object> execute(Map<String, Object> args);
}