package com.agent.tool;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * L5 工具协议层：工具执行引擎对外接口（docs/design/architecture/20260901-tool-engine.md §3）
 * 执行管线：注册表解析 → 权限检查 → Schema 校验 → 幂等拦截 → 超时执行 → 结果规整
 */
public interface ToolEngineService {

    /** 执行工具（写操作必带幂等键，重复键返回首次结果） */
    ToolInvokeResult invoke(String tenantId, String appId, InvokeRequest req);

    /** 注册表快照（Agent System Prompt 工具清单组装用） */
    List<ToolMeta> listTools();

    /** 单工具元数据 */
    Optional<ToolMeta> metaOf(String name);

    /** 按幂等键查历史调用（审计/对账） */
    Optional<ToolInvokeResult> replay(String tenantId, String idempotencyKey);

    /** 调用请求 */
    record InvokeRequest(String tool, Map<String, Object> args, String idempotencyKey) {
    }

    /** 调用结果（code=0 成功；idempotentReplay=true 表示幂等重放未真实执行） */
    record ToolInvokeResult(int code, String message, Map<String, Object> data, boolean idempotentReplay) {

        public static ToolInvokeResult ok(Map<String, Object> data) {
            return new ToolInvokeResult(0, "ok", data, false);
        }

        public static ToolInvokeResult replay(Map<String, Object> data) {
            return new ToolInvokeResult(0, "ok", data, true);
        }
    }
}