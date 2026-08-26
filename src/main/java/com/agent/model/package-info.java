/**
 * L6 模型服务层：LLM 网关（路由/降级/计量）、推理服务、模型注册表。
 * 设计约束：统一 OpenAI 兼容协议；每次调用记录 Token 成本；主备模型降级链。
 */
package com.agent.model;