package com.agent.orchestration.appfactory;

import com.agent.data.application.AppRepository;
import com.agent.data.application.AppRepository.AppRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * AppRegistry 单测：启动装载 / 租户+GLOBAL 可见性 / 未知应用回退 / 单条刷新
 * mock AppRepository，不依赖真实 DB。
 */
class AppRegistryTest {

    private AppRepository repo;
    private AppRegistry registry;

    private static final String CFG = """
            {"role":{"name":"测试应用"},"prompt":{"system":"你是助手"},
             "quota":{"maxSteps":10,"tokenBudget":32000,"timeoutMs":300000,"loopThreshold":3,"maxConcurrency":5}}
            """;

    @BeforeEach
    void setUp() {
        repo = Mockito.mock(AppRepository.class);
        registry = new AppRegistry(repo);
        when(repo.listAll()).thenReturn(List.of(
                new AppRow(1, "tenant-x", "cs_customer_service", "客服", "ENABLED", CFG, 1,
                        Instant.now(), Instant.now()),
                new AppRow(2, "GLOBAL", "tr_booking", "商旅", "ENABLED", CFG, 1,
                        Instant.now(), Instant.now())));
        registry.reloadAll();
    }

    @Test
    void 启动装载_应用可达() {
        assertEquals(2, registry.size());
        assertNotNull(registry.get("tenant-x", "cs_customer_service"));
        assertNotNull(registry.get("GLOBAL", "tr_booking"));
    }

    @Test
    void 租户找不到_回退GLOBAL平台应用() {
        AppDefinition d = registry.get("tenant-y", "tr_booking");
        assertNotNull(d);
        assertEquals("tr_booking", d.appId());
    }

    @Test
    void 未知应用_返回null() {
        assertNull(registry.get("tenant-x", "ghost"));
    }

    @Test
    void listForTenant_含租户内与GLOBAL() {
        assertEquals(2, registry.listForTenant("tenant-x").size());
    }

    @Test
    void refresh_移除已删应用并加载新配置() {
        when(repo.findByAppId("tenant-x", "cs_customer_service"))
                .thenReturn(Optional.of(new AppRow(1, "tenant-x", "cs_customer_service", "客服改", "ENABLED",
                        "{\"role\":{\"name\":\"新\"},\"prompt\":{\"system\":\"s\"},\"quota\":{\"maxSteps\":5,\"tokenBudget\":1,\"timeoutMs\":1,\"maxConcurrency\":1}}",
                        2, Instant.now(), Instant.now())));
        registry.refresh("tenant-x", "cs_customer_service");
        AppDefinition d = registry.get("tenant-x", "cs_customer_service");
        assertNotNull(d);
        assertEquals("新", d.role().name());
        assertEquals(5, d.quota().maxSteps());
    }

    @Test
    void refresh_应用删除后_从缓存移除() {
        when(repo.findByAppId("tenant-x", "cs_customer_service")).thenReturn(Optional.empty());
        registry.refresh("tenant-x", "cs_customer_service");
        assertNull(registry.get("tenant-x", "cs_customer_service"));
        assertNotNull(registry.get("tenant-x", "tr_booking")); // GLOBAL 仍在
    }

    @Test
    void 配置解析缺项_回退null不清零() {
        AppDefinition minimal = AppDefinition.parse("m", "M", "{\"role\":{\"name\":\"x\"}}");
        assertEquals("x", minimal.role().name());
        assertNull(minimal.prompt());
        assertNull(minimal.quota());
        assertEquals(0, minimal.tools().size());
    }
}