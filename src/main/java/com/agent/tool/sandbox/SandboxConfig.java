package com.agent.tool.sandbox;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** L5 执行沙箱：配置装配（docs/design/security/20260905-sandbox.md） */
@Configuration
@EnableConfigurationProperties(SandboxProperties.class)
public class SandboxConfig {
}