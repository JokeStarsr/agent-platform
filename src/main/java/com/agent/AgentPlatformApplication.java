package com.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 企业级智能体平台 · 启动入口
 * 七层架构：access / app / orchestration / capability / tool / model / data
 * 注：McpProperties 的注册在 L5 McpWebConfig（@EnableConfigurationProperties），避免入口类直接引用工具层类型
 * （ArchitectureTest 层依赖守护会拦截跨层注解成员引用）。
 */
@SpringBootApplication
@EnableScheduling
public class AgentPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentPlatformApplication.class, args);
    }
}