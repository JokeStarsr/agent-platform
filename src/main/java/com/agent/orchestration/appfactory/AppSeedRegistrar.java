package com.agent.orchestration.appfactory;

import com.agent.data.application.AppRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * L3 编排层：内置应用种子注册（classpath app-seeds/*.json，幂等 upsert）
 * 依赖注入 AppRegistry 保证其先行装载；种子落库后重载注册表。
 */
@Component
public class AppSeedRegistrar {

    private static final Logger log = LoggerFactory.getLogger(AppSeedRegistrar.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AppRepository repo;
    private final AppRegistry registry;

    public AppSeedRegistrar(AppRepository repo, AppRegistry registry) {
        this.repo = repo;
        this.registry = registry;
    }

    @PostConstruct
    void seed() {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:app-seeds/*.json");
            int upserted = 0;
            for (Resource r : resources) {
                Map<String, Object> seed = JSON.readValue(r.getInputStream(), Map.class);
                String appId = String.valueOf(seed.get("appId"));
                String name = String.valueOf(seed.get("name"));
                String config = JSON.writeValueAsString(seed.get("config"));
                if (repo.upsertSeed("GLOBAL", appId, name, config)) {
                    upserted++;
                    log.info("应用种子落库: {} ({})", appId, name);
                }
            }
            // 种子就绪后重载注册表（覆盖启动时可能先于种子的空装载）
            registry.reloadAll();
            log.info("应用种子注册完成: resources={}, upserted={}", resources.length, upserted);
        } catch (Exception e) {
            log.error("应用种子注册失败: {}", e.getMessage(), e);
        }
    }
}