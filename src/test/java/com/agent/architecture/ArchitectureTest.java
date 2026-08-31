package com.agent.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

/**
 * 架构守护测试（对应 CLAUDE.md 铁律与排期 W1"ArchUnit 架构守护测试初版"）
 * 守护两条铁律：
 *   1. 七层依赖只允许向下（access → app → orchestration → capability → tool → model → data）
 *   2. 应用层禁止直连模型与数据库（统一经 L6 LlmGateway / L4 能力层）
 * 纯字节码分析，不启动 Spring，不调用任何真实服务。
 */
class ArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter().importPackages("com.agent");
    }

    @Test
    void 七层依赖只允许向下() {
        ArchRule rule = layeredArchitecture()
                .consideringAllDependencies()
                .layer("Access").definedBy("..access..")
                .layer("App").definedBy("..app..")
                .layer("Orchestration").definedBy("..orchestration..")
                .layer("Capability").definedBy("..capability..")
                .layer("Tool").definedBy("..tool..")
                .layer("Model").definedBy("..model..")
                .layer("Data").definedBy("..data..")
                // access 是 L1 入口层，任何层都不得反向依赖更高层；以下逐层收紧
                .whereLayer("Access").mayNotBeAccessedByAnyLayer()
                .whereLayer("App").mayOnlyBeAccessedByLayers("Access")
                .whereLayer("Orchestration").mayOnlyBeAccessedByLayers("Access", "App")
                .whereLayer("Capability").mayOnlyBeAccessedByLayers("Access", "App", "Orchestration")
                .whereLayer("Tool").mayOnlyBeAccessedByLayers("Access", "App", "Orchestration", "Capability")
                .whereLayer("Model").mayOnlyBeAccessedByLayers("Access", "App", "Orchestration", "Capability", "Tool")
                .whereLayer("Data").mayOnlyBeAccessedByLayers("Access", "App", "Orchestration", "Capability", "Tool", "Model");
        rule.check(classes);
    }

    @Test
    void 应用层禁止直连模型() {
        noClasses().that().resideInAPackage("..app..")
                .should().dependOnClassesThat().resideInAPackage("org.springframework.ai.chat.model")
                .orShould().dependOnClassesThat().resideInAPackage("org.springframework.ai.chat.client")
                .because("应用层禁止直连模型，统一经 L6 LlmGateway（CLAUDE.md 铁律）")
                .check(classes);
    }

    @Test
    void 应用层禁止直连向量库() {
        noClasses().that().resideInAPackage("..app..")
                .should().dependOnClassesThat().resideInAPackage("org.springframework.ai.vectorstore")
                .because("应用层禁止直连数据库/向量库，统一经 L4 能力层")
                .check(classes);
    }

    @Test
    void capability层模型调用必须经LlmGateway() {
        noClasses().that().resideInAPackage("..capability..")
                .should().dependOnClassesThat().resideInAPackage("org.springframework.ai.chat.model")
                .orShould().dependOnClassesThat().resideInAPackage("org.springframework.ai.chat.client")
                .because("能力层调用 LLM 统一经 L6 LlmGateway，禁止自行拉取 ChatModel")
                .check(classes);
    }
}