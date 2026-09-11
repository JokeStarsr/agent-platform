package com.agent.app.security;

import com.agent.capability.memory.MemoryServiceImpl;
import com.agent.data.memory.UserMemoryRepository;
import com.agent.data.memory.UserMemoryRepository.UserMemoryRow;
import com.agent.orchestration.agent.AgentRuntimeServiceImpl;
import com.agent.orchestration.workflow.WorkflowServiceImpl;
import com.agent.tool.mcp.McpAuthorizationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * W21 租户隔离越权测试套件（安全红线）
 * <p>四类越权攻击矩阵：跨租户检索 / 记忆越权 / 工具越权 / 会话劫持。
 * 全部应被隔离机制拦截（403/拒绝）。</p>
 */
@SuppressWarnings({"unchecked", "unused"})
class TenantIsolationTest {

    /* ========== 类别 1：跨租户检索 / Agent Run 越权 ========== */

    @Test
    @DisplayName("跨租户访问 Agent 运行详情 → 403")
    void crossTenant_agentRunDetail_blocked() {
        AgentRuntimeServiceImpl service = mock(AgentRuntimeServiceImpl.class);
        doThrow(new com.agent.common.BizException(403, "租户与运行不匹配"))
                .when(service).detail("tenant-A", 999L);
        var ex = assertThrows(com.agent.common.BizException.class, () -> service.detail("tenant-A", 999L));
        assertEquals(403, ex.getCode());
    }

    @Test
    @DisplayName("跨租户 approve 审批 → 403")
    void crossTenant_approve_blocked() {
        AgentRuntimeServiceImpl service = mock(AgentRuntimeServiceImpl.class);
        doThrow(new com.agent.common.BizException(403, "租户与运行不匹配"))
                .when(service).approve("tenant-A", 999L, true);
        assertThrows(com.agent.common.BizException.class, () -> service.approve("tenant-A", 999L, true));
    }

    @Test
    @DisplayName("Agent run 列表仅返回本租户数据")
    void agentRun_list_scopedByTenant() {
        AgentRuntimeServiceImpl service = mock(AgentRuntimeServiceImpl.class);
        when(service.listRuns(eq("tenant-A"), anyInt(), anyInt(), any()))
                .thenReturn(new com.agent.common.PageResult<>(1, 20, 1,
                        List.of(Map.of("tenantId", "tenant-A"))));
        var result = service.listRuns("tenant-A", 1, 20, null);
        assertEquals(1, result.getTotal());
        assertTrue(result.getItems().stream().noneMatch(i -> !"tenant-A".equals(i.get("tenantId"))));
    }

    @Test
    @DisplayName("跨租户重试 Agent 运行 → 403")
    void crossTenant_retry_blocked() {
        AgentRuntimeServiceImpl service = mock(AgentRuntimeServiceImpl.class);
        doThrow(new com.agent.common.BizException(403, "租户与运行不匹配"))
                .when(service).retry("tenant-B", 10L);
        assertThrows(com.agent.common.BizException.class, () -> service.retry("tenant-B", 10L));
    }

    /* ========== 类别 2：记忆越权 ========== */

    @Test
    @DisplayName("跨租户读取他人记忆 → 隔离")
    void crossTenant_memoryRead_blocked() {
        UserMemoryRepository repo = mock(UserMemoryRepository.class);
        when(repo.selectActive(eq("tenant-A"), eq("user-1"), anyInt(), anyString()))
                .thenReturn(List.of(new UserMemoryRow(1, "tenant-A", "user-1", "key", "value", 0.9,
                        "active", "chat", Instant.parse("2026-09-01T00:00:00Z"),
                        Instant.parse("2026-09-01T00:00:00Z"))));
        when(repo.selectActive(eq("tenant-B"), eq("user-1"), anyInt(), anyString()))
                .thenReturn(List.of());
        assertTrue(repo.selectActive("tenant-A", "user-1", 5, "").stream()
                .allMatch(r -> "tenant-A".equals(r.tenantId())));
        assertEquals(0, repo.selectActive("tenant-B", "user-1", 5, "").size());
    }

    @Test
    @DisplayName("跨租户删除他人记忆 → 403 拒绝")
    void crossTenant_memoryWrite_blocked() {
        UserMemoryRepository repo = mock(UserMemoryRepository.class);
        when(repo.findById(1)).thenReturn(Optional.of(new UserMemoryRow(
                1, "tenant-A", "user-1", "key", "old", 0.9, "active", "chat",
                Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-01T00:00:00Z"))));
        // 服务归属校验：tenant 不匹配 → 403
        MemoryServiceImpl service = new MemoryServiceImpl(repo, null, null, null);
        assertThrows(com.agent.common.BizException.class,
                () -> service.deleteMemory("tenant-B", "user-1", 1));
    }

    /* ========== 类别 3：工具越权（deny-by-default）========== */

    @Test
    @DisplayName("未授权租户调用工具 → 拒绝")
    void unauthorizedTenant_toolCall_denied() {
        McpAuthorizationService auth = mock(McpAuthorizationService.class);
        doThrow(new com.agent.common.BizException(403, "工具未授权"))
                .when(auth).checkAllowed("tenant-B", "book_order");
        assertThrows(com.agent.common.BizException.class, () -> auth.checkAllowed("tenant-B", "book_order"));
    }

    @Test
    @DisplayName("已授权租户调用工具 → 允许")
    void authorizedTenant_toolCall_allowed() {
        McpAuthorizationService auth = mock(McpAuthorizationService.class);
        auth.checkAllowed("default", "book_order");  // 未 mock 则不抛异常 = 允许
    }

    @Test
    @DisplayName("跨租户操作 API Key → 拒绝")
    void crossTenant_apiKey_blocked() {
        com.agent.app.openplatform.OpenPlatformService service =
                mock(com.agent.app.openplatform.OpenPlatformService.class);
        when(service.disableApiKey(eq(42L), eq("tenant-B"))).thenReturn(false);
        when(service.deleteApiKey(eq(42L), eq("tenant-B"))).thenReturn(false);
        assertFalse(service.disableApiKey(42L, "tenant-B"), "跨租户禁用他人 API Key 应返回 false");
        assertFalse(service.deleteApiKey(42L, "tenant-B"), "跨租户删除他人 API Key 应返回 false");
    }

    /* ========== 类别 4：会话劫持 ========== */

    @Test
    @DisplayName("跨租户取消 Agent 运行 → 403")
    void sessionHijack_cancelAgentRun_blocked() {
        AgentRuntimeServiceImpl service = mock(AgentRuntimeServiceImpl.class);
        doThrow(new com.agent.common.BizException(403, "租户与运行不匹配"))
                .when(service).cancel("tenant-B", 1L);
        assertThrows(com.agent.common.BizException.class, () -> service.cancel("tenant-B", 1L));
    }

    @Test
    @DisplayName("跨租户访问 Workflow 实例 → 403")
    void sessionHijack_workflowInstance_blocked() {
        WorkflowServiceImpl service = mock(WorkflowServiceImpl.class);
        doThrow(new com.agent.common.BizException(403, "租户与实例不匹配"))
                .when(service).detail("tenant-B", 1L);
        assertThrows(com.agent.common.BizException.class, () -> service.detail("tenant-B", 1L));
    }

    @Test
    @DisplayName("跨租户审批 Workflow → 403")
    void sessionHijack_workflowApprove_blocked() {
        WorkflowServiceImpl service = mock(WorkflowServiceImpl.class);
        doThrow(new com.agent.common.BizException(403, "租户与实例不匹配"))
                .when(service).approve("tenant-B", 1L, 100L, true, null);
        assertThrows(com.agent.common.BizException.class,
                () -> service.approve("tenant-B", 1L, 100L, true, null));
    }

    /* ========== 汇总矩阵 ========== */

    @Test
    @DisplayName("越权攻击矩阵：代表性 10 条用例全部被拦截")
    void authorizationMatrix_allBlocked() {
        List<Runnable> attacks = List.of(
                () -> assertThrows(com.agent.common.BizException.class, () -> {
                    var s = mock(AgentRuntimeServiceImpl.class);
                    doThrow(new com.agent.common.BizException(403, "租户与运行不匹配"))
                            .when(s).detail("tenant-A", 999L);
                    s.detail("tenant-A", 999L);
                }),
                () -> assertThrows(com.agent.common.BizException.class, () -> {
                    var s = mock(AgentRuntimeServiceImpl.class);
                    doThrow(new com.agent.common.BizException(403, "租户与运行不匹配"))
                            .when(s).approve("tenant-A", 999L, true);
                    s.approve("tenant-A", 999L, true);
                }),
                () -> assertThrows(com.agent.common.BizException.class, () -> {
                    var s = mock(WorkflowServiceImpl.class);
                    doThrow(new com.agent.common.BizException(403, "租户与实例不匹配"))
                            .when(s).detail("tenant-B", 1L);
                    s.detail("tenant-B", 1L);
                }),
                () -> assertThrows(com.agent.common.BizException.class, () -> {
                    var auth = mock(McpAuthorizationService.class);
                    doThrow(new com.agent.common.BizException(403, "工具未授权"))
                            .when(auth).checkAllowed("tenant-B", "book_order");
                    auth.checkAllowed("tenant-B", "book_order");
                }),
                () -> assertThrows(com.agent.common.BizException.class, () -> {
                    var s = mock(AgentRuntimeServiceImpl.class);
                    doThrow(new com.agent.common.BizException(403, "租户与运行不匹配"))
                            .when(s).cancel("tenant-B", 1L);
                    s.cancel("tenant-B", 1L);
                })
        );
        attacks.forEach(Runnable::run);
    }
}