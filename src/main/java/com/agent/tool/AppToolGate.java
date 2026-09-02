package com.agent.tool;

import com.agent.common.BizException;

/**
 * L5 工具协议层接口：应用工具白名单门控（docs/design/api/20260902-app-factory.md §2.5）
 * <p>依赖反转：ToolEngineServiceImpl（L5）不直接依赖 L3 AppRegistry（违反七层约束），
 * 由 L3 orchestration 提供实现（AppToolGateImpl）；无实现 bean 时不拦截（放行）。</p>
 */
public interface AppToolGate {

    /** 检查工具是否在应用白名单内；未注册应用无条件放行。不在白名单则抛 BizException(403)。 */
    void checkToolAllowed(String tenantId, String appId, String toolName) throws BizException;
}
