package com.agentos.core.config;

import com.agentos.core.agent.AgentLoader;
import com.agentos.core.agent.ContextLoader;
import com.agentos.core.provider.ProviderCatalog;
import com.agentos.core.provider.ProviderService;
import com.agentos.core.session.MessageStore;
import com.agentos.core.session.SessionManager;
import com.agentos.core.session.TurnStore;
import com.agentos.core.turn.AgentService;
import com.agentos.core.turn.DefaultAgentService;
import com.agentos.core.turn.PromptBuilder;
import com.agentos.core.turn.ReActLoop;
import com.agentos.core.turn.TurnBudgetRegistry;
import com.agentos.core.turn.TurnGuard;
import com.agentos.core.turn.TurnRunner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Core 模块的对象装配表，通过模块的 AutoConfiguration.imports 被 Boot 发现。
 *
 * <p>@Bean 方法参数由容器按类型注入，返回值交给容器管理；普通业务类无需添加 @Service。
 * afterName 让 Storage、Provider 的定义先被处理，AgentService 的条件才看得见所需端口。
 * 排序控制装配定义处理，不表示所有对象严格按本文件方法顺序实例化。
 */
@AutoConfiguration(
        afterName = {
            "com.agentos.storage.StorageAutoConfiguration",
            "com.agentos.provider.config.ProviderAutoConfiguration",
            "com.agentos.tool.ToolAutoConfiguration",
            "org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration"
        })
@EnableConfigurationProperties({
    WorkspaceProperties.class,
    TurnProperties.class,
    AuditProperties.class
})
public class CoreAutoConfiguration {
    @Bean
    AgentLoader agentLoader(WorkspaceProperties properties) {
        // 一个工作区可有多个 Agent：answer 等名称最终定位到 root/agents/<name>/AGENT.md。
        return new AgentLoader(properties.root().resolve("agents"));
    }

    @Bean
    ContextLoader contextLoader(WorkspaceProperties properties) {
        // 共享 Bootstrap 从 root 本身读取，而不是每个 Agent 子目录。
        return new ContextLoader(properties.root());
    }

    @Bean
    PromptBuilder promptBuilder(ContextLoader loader) {
        return new PromptBuilder(loader);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(
            TurnBudgetRegistry.class)
    TurnBudgetRegistry turnBudgetRegistry() {
        // 容器内共享一个并发预算表，具体轮次的预算在业务入口登记和清理。
        return new TurnBudgetRegistry();
    }

    @Bean
    TurnRunner turnRunner() {
        return new TurnRunner();
    }

    @Bean
    TurnGuard turnGuard(TurnStore store) {
        return new TurnGuard(store);
    }

    // 必须同时具备模型与存储端口才能装配业务总入口，避免生成“看似存在却无法运行”的服务。
    @Bean
    @ConditionalOnBean({
        ProviderService.class,
        MessageStore.class,
        TurnStore.class,
        SessionManager.class,
        ProviderCatalog.class
    })
    AgentService agentService(
            AgentLoader loader,
            ContextLoader contextLoader,
            ProviderCatalog providers,
            SessionManager sessions,
            TurnStore turns,
            MessageStore messages,
            PromptBuilder prompts,
            TurnGuard guard,
            TurnBudgetRegistry budgets,
            ProviderService provider,
            TurnRunner runner,
            com.agentos.core.tool.ToolCatalog catalog,
            org.springframework.ai.model.tool.ToolCallingManager manager,
            com.agentos.core.tool.Redactor redactor,
            com.agentos.core.audit.ToolInvocationRecorder recorder,
            TurnProperties turnProperties) {
        // ReActLoop 是普通对象，生命周期由持有它的 AgentService 管理，不单独注册成 Bean。
        return new DefaultAgentService(
                loader,
                contextLoader,
                providers,
                sessions,
                turns,
                new ReActLoop(
                        provider,
                        messages,
                        prompts,
                        guard,
                        catalog,
                        new com.agentos.core.turn.ToolExecutor(manager, recorder, redactor),
                        redactor),
                budgets,
                runner,
                turnProperties);
    }
}
