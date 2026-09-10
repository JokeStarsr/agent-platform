package com.agent.common;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 静态目录 URL → index.html 重定向
 * Spring Boot 默认只将根路径 / 映射到 index.html，子目录 URL（/chat/ 等）默认 404；
 * 这里用精确 ViewController 把 13 个管理页目录根指向各自 index.html（精确路径优先于 /** 静态映射）。
 * 对应 docs/design/api/20260902-admin-pages.md §2.5
 */
@Configuration
public class StaticViewRedirectConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        String[][] dirs = {
                {"/chat/", "/chat/index.html"},
                {"/workflow/", "/workflow/index.html"},
                {"/agent/", "/agent/index.html"},
                {"/memory/", "/memory/index.html"},
                {"/rag/", "/rag/index.html"},
                {"/collections/", "/collections/index.html"},
                {"/apps/", "/apps/index.html"},
                {"/mcp-gateway/", "/mcp-gateway/index.html"},
                {"/tool-market/", "/tool-market/index.html"},
                {"/multi-agent/", "/multi-agent/index.html"},
                {"/usage/", "/usage/index.html"},
                {"/skill-hub/", "/skill-hub/index.html"},
                {"/assistant/", "/assistant/index.html"},
                {"/data-agent/", "/data-agent/index.html"},
                {"/cost-dashboard/", "/cost-dashboard/index.html"},
        };
        for (String[] d : dirs) {
            registry.addViewController(d[0]).setViewName("redirect:" + d[1]);
        }
    }
}