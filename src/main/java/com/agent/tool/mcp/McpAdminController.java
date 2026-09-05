package com.agent.tool.mcp;

import com.agent.common.Result;
import com.agent.data.toolgrant.ToolGrantRepository;
import com.agent.tool.ToolMeta;
import com.agent.tool.ToolRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * L5 MCP 网关：管理 API（docs/design/architecture/20260904-mcp-gateway.md §7.2，统一 Result<T>）。
 * 工具清单 / 租户授权维护 / 健康检查。
 */
@RestController
@RequestMapping("/api/mcp")
public class McpAdminController {

    private final ToolRegistry toolRegistry;
    private final ToolGrantRepository grantRepo;
    private final McpServerRegistry registry;
    private final McpOutboundConnector outbound;

    public McpAdminController(ToolRegistry toolRegistry, ToolGrantRepository grantRepo,
                              McpServerRegistry registry, McpOutboundConnector outbound) {
        this.toolRegistry = toolRegistry;
        this.grantRepo = grantRepo;
        this.registry = registry;
        this.outbound = outbound;
    }

    /** 入站暴露的工具清单（含权限，供审计核对） */
    @GetMapping("/tools")
    public Result<List<ToolMeta>> tools() {
        return Result.ok(toolRegistry.listTools());
    }

    /** 某租户工具授权列表（分页由前端二次过滤，v1 全量） */
    @GetMapping("/grants")
    public Result<List<ToolGrantRepository.GrantRow>> grants(@RequestParam String tenantId) {
        return Result.ok(grantRepo.listByTenant(tenantId));
    }

    /** 授权 / 撤销（enabled=true 授权，false 撤销） */
    @PostMapping("/grants")
    public Result<Void> grant(@RequestBody GrantReq req) {
        grantRepo.setEnabled(req.tenantId(), req.toolName(), req.enabled());
        return Result.ok(null);
    }

    /** 网关健康检查（外部探活 / 运维看板） */
    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        return Result.ok(registry.health());
    }

    /** 出站连接器状态（W10 骨架 / W11 完整） */
    @GetMapping("/outbound")
    public Result<List<Map<String, Object>>> outbound() {
        return Result.ok(outbound.status());
    }

    public record GrantReq(String tenantId, String toolName, boolean enabled) {
    }
}
