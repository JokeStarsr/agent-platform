package com.agent.tool.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * L5 MCP 网关配置（agent-platform.mcp.*）
 * <ul>
 *   <li>api-keys：租户 → API Key 映射（外部 MCP Client 鉴权用；key 禁明文入库，走环境变量注入）</li>
 *   <li>rate-limit.global-qps：租户级全局 QPS 上限</li>
 * </ul>
 */
@ConfigurationProperties(prefix = McpConstants.CONFIG_PREFIX)
public class McpProperties {

    /** 租户 → API Key 映射（示例 default: dev-key；生产由环境变量注入） */
    private Map<String, String> apiKeys = Map.of();

    private RateLimit rateLimit = new RateLimit();

    public Map<String, String> getApiKeys() {
        return apiKeys;
    }

    public void setApiKeys(Map<String, String> apiKeys) {
        this.apiKeys = apiKeys == null ? Map.of() : apiKeys;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public void setRateLimit(RateLimit rateLimit) {
        this.rateLimit = rateLimit == null ? new RateLimit() : rateLimit;
    }

    public static class RateLimit {
        /** 租户级全局 QPS 上限，默认 50 */
        private int globalQps = 50;

        public int getGlobalQps() {
            return globalQps;
        }

        public void setGlobalQps(int globalQps) {
            this.globalQps = globalQps;
        }
    }
}
