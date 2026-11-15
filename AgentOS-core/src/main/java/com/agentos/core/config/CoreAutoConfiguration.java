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

/** core 的显式端口装配；没有 storage/provider 时不会强行创建业务服务。 */
@AutoConfiguration(
        afterName = {
            "com.agentos.storage.StorageAutoConfiguration",
            "com.agentos.provider.config.ProviderAutoConfiguration"
        })
@EnableConfigurationProperties({
    WorkspaceProperties.class,
    TurnProperties.class,
    AuditProperties.class
})
public class CoreAutoConfiguration {
    @Bean
    AgentLoader agentLoader(WorkspaceProperties properties) {
        return new AgentLoader(properties.root().resolve("agents"));
    }

    @Bean
    ContextLoader contextLoader(WorkspaceProperties properties) {
        return new ContextLoader(properties.root());
    }

    @Bean
    PromptBuilder promptBuilder(ContextLoader loader) {
        return new PromptBuilder(loader);
    }

    @Bean
    TurnBudgetRegistry turnBudgetRegistry() {
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
            TurnRunner runner) {
        return new DefaultAgentService(
                loader,
                contextLoader,
                providers,
                sessions,
                turns,
                new ReActLoop(provider, messages, prompts, guard),
                budgets,
                runner);
    }
}
