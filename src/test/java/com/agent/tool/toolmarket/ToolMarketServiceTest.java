package com.agent.tool.toolmarket;

import com.agent.common.BizException;
import com.agent.data.toolgrant.ToolGrantRepository;
import com.agent.data.toolmarket.ToolCatalogRepository;
import com.agent.data.toolmarket.ToolCatalogRepository.CatalogRow;
import com.agent.tool.ToolMeta;
import com.agent.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ToolMarketServiceTest {

    private ToolCatalogRepository repo;
    private ToolRegistry toolRegistry;
    private ToolGrantRepository grantRepo;
    private ToolMarketService service;

    private static final String TC = """
            [{"name":"正常","arguments":{"days":7},"expectCode":0}]
            """;

    private CatalogRow draftRow(long id) {
        return new CatalogRow(id, "report_daily", 1, "日报摘要",
                "生成每日业务报表摘要，供运营查看关键指标趋势（演示工具）",
                "data", "{\"type\":\"object\",\"properties\":{}}", "READ",
                "SELF_REGISTERED", null, null, "DRAFT", true, "u1", TC, null, null);
    }

    @BeforeEach
    void setUp() {
        repo = mock(ToolCatalogRepository.class);
        toolRegistry = mock(ToolRegistry.class);
        grantRepo = mock(ToolGrantRepository.class);
        service = new ToolMarketService(repo, toolRegistry, grantRepo);
    }

    private ToolMarketService.ToolRegRequest req() {
        return new ToolMarketService.ToolRegRequest("report_daily", "日报摘要",
                "生成每日业务报表摘要，供运营查看关键指标趋势（演示工具）",
                "data", "{\"type\":\"object\",\"properties\":{}}", "READ", "MOCK",
                List.of(Map.of("name", "正常", "arguments", Map.of("days", 7), "expectCode", 0)));
    }

    @Test
    void register_savesDraft() {
        when(repo.existsByName("report_daily")).thenReturn(false);
        when(repo.insert(any())).thenAnswer(inv -> draftRow(1L));

        CatalogRow row = service.register(req(), "u1");

        assertEquals("DRAFT", row.status());
        assertEquals("SELF_REGISTERED", row.source());
        verify(repo).insert(any());
    }

    @Test
    void register_duplicateName_409() {
        when(repo.existsByName("report_daily")).thenReturn(true);

        assertThrows(BizException.class, () -> service.register(req(), "u1"));
        verify(repo, never()).insert(any());
    }

    @Test
    void publish_draft_smokePass_registersAndGrants() {
        when(repo.findById(1L)).thenReturn(Optional.of(draftRow(1L)));
        when(repo.findByCurrent("report_daily")).thenReturn(Optional.of(draftRow(1L)));

        service.publish(1L, "default");

        verify(repo).updateStatus(1L, "PUBLISHED");
        verify(toolRegistry).register(any(DynamicAgentTool.class));
        verify(grantRepo).setEnabled("default", "report_daily", true);
    }

    @Test
    void publish_alreadyPublished_409() {
        CatalogRow published = new CatalogRow(1L, "report_daily", 1, "日报摘要", "desc",
                "data", "{}", "READ", "SELF_REGISTERED", null, null,
                "PUBLISHED", true, "u1", TC, null, null);
        when(repo.findById(1L)).thenReturn(Optional.of(published));

        assertThrows(BizException.class, () -> service.publish(1L, "default"));
        verify(toolRegistry, never()).register(any());
    }

    @Test
    void offShelf_unregistersAndMarks() {
        CatalogRow published = new CatalogRow(1L, "report_daily", 1, "日报摘要", "desc",
                "data", "{}", "READ", "SELF_REGISTERED", null, null,
                "PUBLISHED", true, "u1", TC, null, null);
        when(repo.findById(1L)).thenReturn(Optional.of(published));

        service.offShelf(1L);

        verify(toolRegistry).unregister("report_daily");
        verify(repo).updateStatus(1L, "OFF_SHELF");
    }

    @Test
    void preview_executesDynamicTool() {
        when(repo.findById(1L)).thenReturn(Optional.of(draftRow(1L)));

        Map<String, Object> out = service.preview(1L, Map.of("days", 7));

        assertTrue(out.containsKey("mock"));
        assertTrue(out.containsKey("echoRequestId"));
    }

    @Test
    void newVersion_incrementsVersion() {
        when(repo.findById(1L)).thenReturn(Optional.of(draftRow(1L)));
        when(repo.insert(any())).thenAnswer(inv -> draftRow(1L));

        CatalogRow row = service.newVersion(1L, req(), "u1");

        assertEquals("DRAFT", row.status());
        // insert 收到的是 version=0（版本号由 Repository 内部 maxVersion+1 计算）
        verify(repo).insert(argThat(r -> r.version() == 0));
    }
}