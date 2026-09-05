package com.agent.tool.mcp;

import com.agent.data.toolgrant.ToolGrantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * L5 MCP 网关：租户级工具授权判定（deny-by-default）。
 * <p>MCP 入站调用无 appId，授权从 per-app 白名单切到 per-tenant t_tool_grant（设计 §3.4/§5）。
 * 工具默认"非授权不可用"；表不可用（未建表/降级）时保守拒绝并告警，不静默放行。</p>
 */
@Service
public class McpAuthorizationService {

    private static final Logger log = LoggerFactory.getLogger(McpAuthorizationService.class);

    private final ToolGrantRepository repo;

    public McpAuthorizationService(ToolGrantRepository repo) {
        this.repo = repo;
    }

    /** 校验租户是否被授权调用某工具；未授权抛 403，由 AgentToolCallback 转为 MCP isError */
    public void checkAllowed(String tenantId, String toolName) {
        boolean allowed;
        try {
            allowed = repo.isAllowed(tenantId, toolName);
        } catch (RuntimeException e) {
            // 表不可用属运行异常，保守拒绝而非放行（安全红线）
            log.error("工具授权判定异常，按拒绝处理 tenant={} tool={}", tenantId, toolName, e);
            allowed = false;
        }
        if (!allowed) {
            throw new McpToolExecutionException(toolName,
                    "租户 " + tenantId + " 未被授权调用工具 " + toolName, null);
        }
    }
}
