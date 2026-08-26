/**
 * L5 工具与协议层：MCP 网关、Function Call 引擎、外部系统适配器、执行沙箱。
 * 设计约束：工具一律经 MCP 注册；写操作需幂等键；沙箱默认无外网。
 */
package com.agent.tool;