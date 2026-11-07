package com.agentos.boot;

import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameMatching;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.GeneralCodingRules.ACCESS_STANDARD_STREAMS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 把 CLAUDE.md「不可违反的设计约束」里能机械检查的部分写成测试。
 *
 * <p>放在 AgentOS-boot：它依赖所有模块，能看到全部生产代码。违反任一条，{@code mvn verify} 失败。
 * 规则都带 {@code allowEmptyShould(true)}：骨架阶段还没有对应的类，不能因为"没东西可查"而失败。
 * 正因为允许为空，{@link #importsEveryModule()} 必须先确认扫描范围覆盖了其他模块，否则所有规则会静默通过。
 */
class ArchitectureRulesTest {

    private static final String TOOL_CALLING_MANAGER =
            "org.springframework.ai.model.tool.ToolCallingManager";
    private static final String CHAT_CLIENT_REQUEST_SPEC =
            "org.springframework.ai.chat.client.ChatClient$ChatClientRequestSpec";

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        // 不能加 DO_NOT_INCLUDE_JARS：mvn verify 时兄弟模块以 target/*.jar 在 classpath 上，
        // 排除 jar 后只剩 boot 自己的类。第三方 jar 由 importPackages("com.agentos") 过滤掉。
        classes =
                new ClassFileImporter()
                        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPackages("com.agentos");
    }

    @Test
    @DisplayName("扫描范围覆盖其他模块（防止规则空转）")
    void importsEveryModule() {
        assertThat(classes.contain("com.agentos.cli.AgentOSCli")).as("cli 模块").isTrue();
        assertThat(classes.contain("com.agentos.boot.AgentOSApplication")).as("boot 模块").isTrue();
    }

    @Test
    @DisplayName("约束 1：主链路不用 ToolCallingAdvisor")
    void noToolCallingAdvisor() {
        noClasses()
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName(
                        "org.springframework.ai.chat.client.advisor.ToolCallingAdvisor")
                .because("循环自实现，工具只经 ToolExecutor 执行")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    @DisplayName("约束 1、2：ChatClient 辅助调用不传 tools")
    void chatClientWithoutTools() {
        noClasses()
                .should()
                .callMethodWhere(
                        target(nameMatching("tools|toolCallbacks"))
                                .and(target(owner(assignableTo(CHAT_CLIENT_REQUEST_SPEC)))))
                .because("ChatClient 只用于辅助调用，传 tools 会出现第二个工具执行入口")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    @DisplayName("约束 2：不自建 ToolCallingManager")
    void noSelfBuiltToolCallingManager() {
        noClasses()
                .should()
                .callMethod(TOOL_CALLING_MANAGER, "builder")
                .orShould()
                .dependOnClassesThat()
                .haveFullyQualifiedName(
                        "org.springframework.ai.model.tool.DefaultToolCallingManager")
                .because("必须注入自动配置的 Bean，否则 spring.ai.tools.limits.*、异常处理器、观测都不生效")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    @DisplayName("约束 2：只有 ToolExecutor 调用 executeToolCalls")
    void onlyToolExecutorExecutesTools() {
        noClasses()
                .that()
                .doNotHaveSimpleName("ToolExecutor")
                .should()
                .callMethodWhere(
                        target(name("executeToolCalls"))
                                .and(target(owner(assignableTo(TOOL_CALLING_MANAGER)))))
                .because("工具只有一个执行入口")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    @DisplayName("约束 7：不用 ThreadLocal 传上下文，MDC 只写不读")
    void noThreadLocalContext() {
        noClasses()
                .should()
                .dependOnClassesThat()
                .areAssignableTo(ThreadLocal.class)
                .because("上下文经 ToolCallingChatOptions.toolContext(...) 传递")
                .allowEmptyShould(true)
                .check(classes);
        noClasses()
                .should()
                .callMethod("org.slf4j.MDC", "get", "java.lang.String")
                .because("MDC 只用于日志关联，业务逻辑不能从 MDC 读上下文")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    @DisplayName("约束 17：不使用 SecurityManager")
    void noSecurityManager() {
        noClasses()
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("java.lang.SecurityManager")
                .because("JDK 21 已不可用，Sandbox 用应用层白名单")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    @DisplayName("依赖倒置：core 不依赖实现模块")
    void coreDependsOnNoImplementation() {
        noClasses()
                .that()
                .resideInAPackage("com.agentos.core..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "com.agentos.provider..",
                        "com.agentos.memory..",
                        "com.agentos.tool..",
                        "com.agentos.storage..",
                        "com.agentos.web..",
                        "com.agentos.channel..",
                        "com.agentos.cli..",
                        "com.agentos.boot..")
                .because("契约放 core，实现放具体模块")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    @DisplayName("REST 接口只出现在 web 模块")
    void restControllersOnlyInWeb() {
        classes()
                .that()
                .areAnnotatedWith("org.springframework.web.bind.annotation.RestController")
                .or()
                .areAnnotatedWith("org.springframework.web.bind.annotation.RestControllerAdvice")
                .should()
                .resideInAPackage("com.agentos.web..")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    @DisplayName("编码规范：构造器注入、SLF4J、不直接写 System.out（CLI 除外）")
    void generalCodingRules() {
        NO_CLASSES_SHOULD_USE_FIELD_INJECTION.allowEmptyShould(true).check(classes);
        NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING.allowEmptyShould(true).check(classes);
        noClasses()
                .that()
                .resideOutsideOfPackages("com.agentos.cli..", "com.agentos.channel.cli..")
                .should(ACCESS_STANDARD_STREAMS)
                .because("日志走 SLF4J；只有命令行模块直接面向终端输出")
                .allowEmptyShould(true)
                .check(classes);
    }
}
