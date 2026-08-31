package com.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 企业级智能体平台 · 启动入口
 * 七层架构：access / app / orchestration / capability / tool / model / data
 */
@SpringBootApplication
@EnableScheduling
public class AgentPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentPlatformApplication.class, args);
    }
}