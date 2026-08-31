package com.agent.tool;

/**
 * L5 工具协议层：工具权限等级（docs/design/architecture/20260901-tool-engine.md §2.1）
 * READ 只读；WRITE 写操作（须经 HITL + 幂等键）；PAYMENT 支付级（本期仅注册不放开）
 */
public enum ToolPermission {
    READ, WRITE, PAYMENT
}