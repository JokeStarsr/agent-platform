package com.agent.tool.mcp;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * L5 MCP 网关：Web 层装配（docs/design/architecture/20260904-mcp-gateway.md §3.3）。
 * <p>McpAuthFilter 用 FilterRegistrationBean 注册（URL 限定 /mcp/*，Order 最高优先），而非裸
 * {@code @Component}——后者会被 {@code @WebMvcTest} 切片自动实例化并要求审计/限流 bean 而启动失败；
 * 本配置与普通 {@code @Configuration} 一样被 WebMvcTest 排除，MCP 鉴权只在完整应用生效。</p>
 * <p>McpProperties（L5 配置）也在本类注册，避免入口类（L1）跨层引用工具层类型（ArchitectureTest 铁律）。</p>
 */
@Configuration
@EnableConfigurationProperties(McpProperties.class)
public class McpWebConfig {

    @Bean
    public FilterRegistrationBean<McpAuthFilter> mcpAuthFilter(McpProperties properties,
                                                               McpAuditService audit,
                                                               McpRateLimiter rateLimiter) {
        FilterRegistrationBean<McpAuthFilter> reg =
                new FilterRegistrationBean<>(new McpAuthFilter(properties, audit, rateLimiter));
        reg.addUrlPatterns(McpConstants.ENDPOINT_PREFIX + "/*");
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 5);
        return reg;
    }
}