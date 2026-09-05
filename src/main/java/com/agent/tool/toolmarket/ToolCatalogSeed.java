package com.agent.tool.toolmarket;

import com.agent.data.toolmarket.ToolCatalogRepository;
import com.agent.tool.ToolMeta;
import com.agent.tool.ToolRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * L5 工具市场：内置工具启动同步（docs/design/architecture/20260905-tool-marketplace.md §2.1）。
 * <p>应用启动把 ToolRegistry 现有（W5-W8 代码注册）工具作为 BUILTIN v1 同步进 t_tool_catalog：
 * 目录名 & 当前已发布状态 → 存量工具立即可见于目录、可管理、可被 stats/外部消费。
 * 幂等：工具名已存在则跳过（保留手工登记的历史版本）。</p>
 */
@Component
public class ToolCatalogSeed {

    private static final Logger log = LoggerFactory.getLogger(ToolCatalogSeed.class);

    private final ToolRegistry toolRegistry;
    private final ToolCatalogRepository repo;

    public ToolCatalogSeed(ToolRegistry toolRegistry, ToolCatalogRepository repo) {
        this.toolRegistry = toolRegistry;
        this.repo = repo;
    }

    @PostConstruct
    public void syncBuiltinTools() {
        int synced = 0;
        for (ToolMeta meta : toolRegistry.listTools()) {
            if (repo.existsByName(meta.name())) {
                continue;
            }
            repo.insert(new ToolCatalogRepository.CatalogRow(
                    0, meta.name(), 0, meta.name(), meta.description(),
                    categoryOf(meta.name()), meta.parameters(), meta.permission().name(),
                    "BUILTIN", null, null,
                    "PUBLISHED", true, "platform", "[]",
                    null, null));
            synced++;
        }
        if (synced > 0) {
            log.info("工具目录同步完成：内置工具 {} 个登记为 BUILTIN/PUBLISHED", synced);
        }
    }

    private String categoryOf(String toolName) {
        if (toolName.startsWith("policy") || toolName.contains("order") || toolName.startsWith("book")
                || toolName.contains("flight") || toolName.contains("hotel")
                || toolName.startsWith("query_recent_o") || toolName.startsWith("send_coupon")
                || toolName.startsWith("refund")) {
            return "business";
        }
        return "utility";
    }
}