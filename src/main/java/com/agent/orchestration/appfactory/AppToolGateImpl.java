package com.agent.orchestration.appfactory;

import com.agent.common.BizException;
import com.agent.tool.AppToolGate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * L3 编排层：AppToolGate 实现（docs/design/api/20260902-app-factory.md §2.5）
 * 应用注册且工具不在白名单 → 403；未注册应用 → 放行。
 */
@Component
public class AppToolGateImpl implements AppToolGate {

    private final AppRegistry registry;

    public AppToolGateImpl(AppRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void checkToolAllowed(String tenantId, String appId, String toolName) throws BizException {
        if (appId == null || appId.isBlank()) {
            return; // 未传 appId → 放行（兼容旧路径）
        }
        AppDefinition d = registry.get(tenantId, appId);
        if (d == null) {
            return; // 未注册应用 → 放行
        }
        List<String> whitelist = AppAssembler.toolWhitelist(d);
        if (whitelist.isEmpty()) {
            return; // 无白名单 → 全部放行
        }
        if (!whitelist.contains(toolName)) {
            throw new BizException(403, "工具 '" + toolName + "' 不在应用 '" + appId + "' 的白名单中");
        }
    }
}
