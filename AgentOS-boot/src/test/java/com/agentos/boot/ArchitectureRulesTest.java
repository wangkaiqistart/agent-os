package com.agentos.boot;

import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameMatching;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.GeneralCodingRules.ACCESS_STANDARD_STREAMS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 把 CLAUDE.md「不可违反的设计约束」里能机械检查的部分写成测试。
 *
 * <p>放在 AgentOS-boot：它依赖所有模块，能看到全部生产代码。违反任一条，{@code mvn verify} 失败。
 * 规则都带 {@code allowEmptyShould(true)}：骨架阶段还没有对应的类，不能因为"没东西可查"而失败。
 * 正因为允许为空，{@link #importsEveryModule()} 必须先确认扫描范围覆盖了其他模块，否则所有规则会静默通过。
 *
 * <p>ArchUnit 不是运行接口的测试，而是读取编译后的类文件，检查“哪些类依赖了哪些类”。
 * 它适合守住模块边界和禁止某些 API 的架构规则。
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
        // 只导入生产代码，并按 com.agentos 过滤第三方依赖。
        classes =
                new ClassFileImporter()
                        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPackages("com.agentos");
    }

    @Test
    @DisplayName("扫描范围覆盖其他模块（防止规则空转）")
    void importsEveryModule() {
        // 如果扫描范围错了，下面的规则可能因为没有候选类而“假通过”。
        assertThat(classes.contain("com.agentos.cli.AgentOSCli")).as("cli 模块").isTrue();
        assertThat(classes.contain("com.agentos.boot.AgentOSApplication")).as("boot 模块").isTrue();
        for (String type :
                java.util.List.of(
                        "com.agentos.core.turn.ReActLoop",
                        "com.agentos.provider.chat.DefaultProviderService",
                        "com.agentos.tool.http.HttpTools",
                        "com.agentos.tool.govern.GovernedToolCallback",
                        "com.agentos.core.turn.ToolExecutor",
                        "com.agentos.storage.audit.JpaToolInvocationRecorder",
                        "com.agentos.storage.session.JpaTurnStore",
                        "com.agentos.channel.cli.CliChannel")) {
            assertThat(classes.contain(type)).as(type).isTrue();
        }
    }

    @Test
    @DisplayName("约束 1：主链路不用 ToolCallingAdvisor")
    void noToolCallingAdvisor() {
        // 主链路必须由项目自己的 ReActLoop 驱动，不能偷偷启用 Advisor 的第二套循环。
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
        // ChatClient 只允许做没有工具的辅助调用，工具执行只能有一个入口。
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
        // 必须使用 Spring 自动配置提供的 ToolCallingManager，才能保留限流、观测和异常处理。
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
        // 任何其他类直接调用 executeToolCalls 都会绕过统一治理层。
        noClasses()
                .that()
                .doNotHaveFullyQualifiedName("com.agentos.core.turn.ToolExecutor")
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
        // ThreadLocal 容易在线程复用时串请求；业务上下文应显式放进 ToolContext。
        noClasses()
                .should()
                .dependOnClassesThat()
                .areAssignableTo(ThreadLocal.class)
                .because("上下文经 ToolCallingChatOptions.toolContext(...) 传递")
                .allowEmptyShould(true)
                .check(classes);
        noClasses()
                .should()
                .callMethodWhere(
                        target(nameMatching("get|getDequeByKey|popByKey|getMDCAdapter"))
                                .and(target(owner(name("org.slf4j.MDC")))))
                .because("MDC 只用于日志关联，业务逻辑不能从 MDC 读上下文")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    @DisplayName("约束 17：不使用 SecurityManager")
    void noSecurityManager() {
        // JDK 21 不再依赖 SecurityManager，项目使用应用层 Sandbox 白名单。
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
        // core 只能定义抽象，不能反向依赖 provider、web、storage 等实现模块。
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
    @DisplayName("FR-004：只有 provider 模块依赖 Spring AI OpenAI 实现")
    void onlyProviderDependsOnSpringAiOpenAi() {
        // 其他模块只通过 core 的 ProviderService 调模型，不能直接拿 OpenAI 实现类。
        noClasses()
                .that()
                .resideOutsideOfPackage("com.agentos.provider..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("org.springframework.ai.openai..")
                .because("模型实现被封装在 provider 模块内，调用方只依赖 core 契约")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    @DisplayName("FR-004、FR-030：任何 @Bean 方法都不能返回 ChatModel 或 EmbeddingModel")
    void noChatOrEmbeddingModelBeans() {
        // 一旦注册成 Bean，就有人会靠扫描容器里的 ChatModel 区分 Provider，
        // 也会激活 Spring AI 的向量库等自动配置。
        noMethods()
                .that()
                .areAnnotatedWith("org.springframework.context.annotation.Bean")
                .should()
                .haveRawReturnType(assignableTo("org.springframework.ai.chat.model.ChatModel"))
                .orShould()
                .haveRawReturnType(assignableTo("org.springframework.ai.embedding.EmbeddingModel"))
                .because("ChatModel 只存在于 ProviderService 内部的连接映射里")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    @DisplayName("REST 接口只出现在 web 模块")
    void restControllersOnlyInWeb() {
        // REST 入口集中在 web，避免在 core 或 provider 中混入 HTTP 细节。
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
        // 统一使用构造器注入和 SLF4J；只有 CLI 可以直接接触标准输出流。
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

    @Test
    void transactionalExternalCallsAreRejectedIncludingLocalDelegates() {
        for (Class<?> fixture :
                java.util.List.of(MethodTransaction.class, ClassTransaction.class)) {
            assertThatThrownBy(() ->
                            checkTransactionalCalls(new ClassFileImporter().importClasses(fixture)))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("事务不得包裹外呼或等待");
        }
        checkTransactionalCalls(new ClassFileImporter().importClasses(StorageTransaction.class));
    }

    private static void checkTransactionalCalls(JavaClasses imported) {
        classes()
                .should(
                        new ArchCondition<>("事务不得包裹外呼或等待") {
                            @Override
                            public void check(
                                    com.tngtech.archunit.core.domain.JavaClass type,
                                    ConditionEvents events) {
                                for (JavaCodeUnit unit : type.getCodeUnits()) {
                                    if (transactional(type) || transactional(unit)) {
                                        checkCalls(unit, new java.util.HashSet<>(), events);
                                    }
                                }
                            }
                        })
                .check(imported);
    }

    private static boolean transactional(
            com.tngtech.archunit.core.domain.properties.CanBeAnnotated element) {
        return element.isAnnotatedWith("org.springframework.transaction.annotation.Transactional")
                || element.isMetaAnnotatedWith(
                        "org.springframework.transaction.annotation.Transactional")
                || element.isAnnotatedWith("jakarta.transaction.Transactional");
    }

    private static void checkCalls(
            JavaCodeUnit unit, java.util.Set<JavaCodeUnit> visited, ConditionEvents events) {
        if (!visited.add(unit)) {
            return;
        }
        for (JavaMethodCall call : unit.getMethodCallsFromSelf()) {
            var targetType = call.getTargetOwner();
            String method = call.getName();
            boolean external =
                    (method.matches("call|stream")
                                    && (targetType.isAssignableTo(
                                                    "com.agentos.core.provider.ProviderService")
                                            || targetType.isAssignableTo(
                                                    "org.springframework.ai.chat.model.ChatModel")
                                            || targetType.isAssignableTo(
                                                    "org.springframework.ai.tool.ToolCallback")))
                            || (method.equals("executeToolCalls")
                                    && targetType.isAssignableTo(TOOL_CALLING_MANAGER))
                            || (method.equals("process")
                                    && targetType
                                            .getName()
                                            .equals("com.agentos.core.turn.AgentService"))
                            || (method.equals("execute")
                                    && targetType
                                            .getName()
                                            .equals("com.agentos.core.turn.ToolExecutor"))
                            || (method.matches("send|sendAsync")
                                    && targetType.isAssignableTo("java.net.http.HttpClient"))
                            || (method.matches("get|join")
                                    && targetType.isAssignableTo("java.util.concurrent.Future"))
                            || (method.equals("sleep")
                                    && targetType.getName().equals("java.lang.Thread"));
            if (external) {
                events.add(
                        SimpleConditionEvent.violated(
                                unit, "事务不得包裹外呼或等待：" + call.getDescription()));
            }
            // 只追同类委托，跨 Bean、异步和程序式事务仍以真实 I/O 动态验证为准。
            if (targetType.equals(unit.getOwner())) {
                call.getTarget()
                        .resolveMember()
                        .ifPresent(next -> checkCalls(next, visited, events));
            }
        }
    }

    @Test
    void externalCallsAreOutsideDeclarativeTransactions() {
        checkTransactionalCalls(classes);
        noClasses()
                .that()
                .resideOutsideOfPackage("com.agentos.storage..")
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName(
                        "org.springframework.transaction.support.TransactionTemplate")
                .because("程序式事务集中在存储模块，外呼编排不能包住数据库事务")
                .check(classes);
    }

    @Test
    void capabilityPathsRemainCoveredAndOnlyManagedProcessStartsChildren() {
        for (String type :
                java.util.List.of(
                        "com.agentos.tool.file.FileTools", "com.agentos.tool.process.ShellTools",
                        "com.agentos.tool.mcp.McpClientService",
                                "com.agentos.tool.interaction.AskUserTools",
                        "com.agentos.tool.search.WebSearchTools",
                                "com.agentos.web.tool.ToolCatalogController",
                        "com.agentos.boot.ToolCatalogApplication",
                                "com.agentos.cli.ToolListCommand")) {
            assertThat(classes.contain(type)).as(type).isTrue();
        }
        noClasses()
                .that()
                .doNotHaveFullyQualifiedName("com.agentos.tool.process.ManagedProcess")
                .should()
                .callMethod(ProcessBuilder.class, "start")
                .because("子进程必须由统一所有权、环境和有界清理实现创建")
                .check(classes);
        noClasses()
                .that()
                .resideInAnyPackage("com.agentos.web.tool..", "com.agentos.cli..")
                .should()
                .dependOnClassesThat()
                .areAssignableTo(org.springframework.ai.model.tool.ToolCallingManager.class)
                .because("查询和CLI只使用core端口，不直接执行工具")
                .check(classes);
    }

    static class MethodTransaction {
        @org.springframework.transaction.annotation.Transactional
        void run(com.agentos.core.provider.ProviderService provider) {
            delegate(provider);
        }

        private void delegate(com.agentos.core.provider.ProviderService provider) {
            provider.call(null);
        }
    }

    @org.springframework.transaction.annotation.Transactional
    static class ClassTransaction {
        void run(org.springframework.ai.tool.ToolCallback tool) {
            tool.call("{}");
        }
    }

    static class StorageTransaction {
        @org.springframework.transaction.annotation.Transactional(readOnly = true)
        int count() {
            return 1;
        }
    }
}
