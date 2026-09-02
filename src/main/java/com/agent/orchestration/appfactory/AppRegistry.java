package com.agent.orchestration.appfactory;

import com.agent.data.application.AppRepository;
import com.agent.data.application.AppRepository.AppRow;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * L3 编排层：应用注册表（docs/design/api/20260902-app-factory.md §2.2）
 * 启动全量装载 t_app → 内存 AppDefinition；未知应用返回 null（消费点回退现状，不破坏行为）。
 * 读多写少：写 API 后调 refresh 单条重载。
 */
@Service
public class AppRegistry {

    private static final Logger log = LoggerFactory.getLogger(AppRegistry.class);

    private record AppKey(String tenantId, String appId) {
    }

    private final AppRepository repo;

    private volatile Map<AppKey, AppDefinition> cache = Map.of();

    public AppRegistry(AppRepository repo) {
        this.repo = repo;
    }

    @PostConstruct
    void load() {
        reloadAll();
    }

    /** 全量重载（种子注册后、外部维护后调用） */
    public synchronized void reloadAll() {
        Map<AppKey, AppDefinition> next = new HashMap<>();
        int enabled = 0;
        for (AppRow row : repo.listAll()) {
            AppDefinition d = AppDefinition.parse(row.appId(), row.name(), row.configJson()).withStatus(row.status());
            next.put(new AppKey(row.tenantId(), row.appId()), d);
            if ("ENABLED".equals(row.status())) {
                enabled++;
            }
        }
        cache = Map.copyOf(next);
        log.info("AppRegistry 装载完成: {} 个应用（enabled={}）", next.size(), enabled);
    }

    /** 单条重载（创建/启停/配置变更后调用） */
    public void refresh(String tenantId, String appId) {
        synchronized (this) {
            Map<AppKey, AppDefinition> next = new HashMap<>(cache);
            next.entrySet().removeIf(e -> e.getKey().tenantId().equals(tenantId) && e.getKey().appId().equals(appId));
            repo.findByAppId(tenantId, appId).ifPresent(row ->
                    next.put(new AppKey(row.tenantId(), row.appId()),
                            AppDefinition.parse(row.appId(), row.name(), row.configJson()).withStatus(row.status())));
            cache = Map.copyOf(next);
        }
    }

    /** 按 (tenantId, appId) 取应用；租户内找不到回退 GLOBAL 平台应用 */
    public AppDefinition get(String tenantId, String appId) {
        AppDefinition d = cache.get(new AppKey(tenantId, appId));
        return d != null ? d : cache.get(new AppKey("GLOBAL", appId));
    }

    /** 当前租户可见应用（租户内 + GLOBAL） */
    public List<AppDefinition> listForTenant(String tenantId) {
        return cache.entrySet().stream()
                .filter(e -> e.getKey().tenantId().equals(tenantId) || e.getKey().tenantId().equals("GLOBAL"))
                .map(Map.Entry::getValue)
                .toList();
    }

    public int size() {
        return cache.size();
    }
}