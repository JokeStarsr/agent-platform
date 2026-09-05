package com.agent.tool.mcp;

import com.agent.data.toolgrant.ToolGrantRepository;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpAuthorizationServiceTest {

    @Test
    void checkAllowed_allowed_noThrow() {
        ToolGrantRepository repo = mock(ToolGrantRepository.class);
        when(repo.isAllowed("t1", "compare_flight")).thenReturn(true);
        McpAuthorizationService svc = new McpAuthorizationService(repo);
        assertDoesNotThrow(() -> svc.checkAllowed("t1", "compare_flight"));
    }

    @Test
    void checkAllowed_denied_throws() {
        ToolGrantRepository repo = mock(ToolGrantRepository.class);
        when(repo.isAllowed("t1", "book_order")).thenReturn(false);
        McpAuthorizationService svc = new McpAuthorizationService(repo);
        assertThrows(McpToolExecutionException.class, () -> svc.checkAllowed("t1", "book_order"));
    }

    @Test
    void checkAllowed_repoException_throwsDenied() {
        ToolGrantRepository repo = mock(ToolGrantRepository.class);
        when(repo.isAllowed("t1", "compare_flight")).thenThrow(new RuntimeException("DB down"));
        McpAuthorizationService svc = new McpAuthorizationService(repo);
        // 表不可用 → 保守拒绝（安全红线）
        assertThrows(McpToolExecutionException.class, () -> svc.checkAllowed("t1", "compare_flight"));
    }
}
