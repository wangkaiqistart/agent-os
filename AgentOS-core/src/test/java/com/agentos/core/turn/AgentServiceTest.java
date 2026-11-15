package com.agentos.core.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.agentos.core.agent.AgentLoader;
import com.agentos.core.agent.ContextLoader;
import com.agentos.core.provider.ProviderCatalog;
import com.agentos.core.session.SessionKey;
import com.agentos.core.support.AgentFixtures;
import com.agentos.core.support.InMemoryMessageStore;
import com.agentos.core.support.InMemorySessionManager;
import com.agentos.core.support.InMemoryTurnStore;
import com.agentos.core.support.ScriptedProviderService;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

class AgentServiceTest {
    @Test
    void processOrchestratesOneTurnAndSendsOneTerminalEvent() throws Exception {
        var workspace = Files.createTempDirectory("agent-service");
        AgentFixtures.agent(workspace, "assistant");
        ScriptedProviderService provider =
                new ScriptedProviderService()
                        .enqueue(
                                new ChatResponse(
                                        List.of(new Generation(new AssistantMessage("hello")))));
        InMemorySessionManager sessions = new InMemorySessionManager();
        InMemoryTurnStore turns = new InMemoryTurnStore(sessions);
        var service =
                new DefaultAgentService(
                        new AgentLoader(workspace.resolve("agents")),
                        new ContextLoader(workspace),
                        mock(ProviderCatalog.class),
                        sessions,
                        turns,
                        new ReActLoop(
                                provider,
                                new InMemoryMessageStore(),
                                new PromptBuilder(new ContextLoader(workspace)),
                                new TurnGuard(turns)),
                        new TurnBudgetRegistry());
        AtomicInteger completes = new AtomicInteger();
        TurnSink sink =
                new TurnSink() {
                    @Override
                    public void onComplete(TurnResult result) {
                        completes.incrementAndGet();
                    }
                };

        TurnResult result =
                service.process(new ProcessRequest("cli", "u", "assistant", "hi", "cli"), sink);

        assertThat(result.status()).isEqualTo(TurnStatus.SUCCEEDED);
        assertThat(result.text()).isEqualTo("hello");
        assertThat(result.iterations()).isEqualTo(1);
        assertThat(result.persistenceStatus()).isEqualTo(PersistenceStatus.CONFIRMED);
        assertThat(provider.calls()).isOne();
        assertThat(completes).hasValue(1);
        assertThat(MDC.get("turnId")).isNull();
    }

    @Test
    void blankMessageIsRejectedBeforeCreatingSession() throws Exception {
        var workspace = Files.createTempDirectory("agent-service-blank");
        AgentFixtures.agent(workspace, "assistant");
        var sessions = new InMemorySessionManager();
        var service =
                new DefaultAgentService(
                        new AgentLoader(workspace.resolve("agents")),
                        new ContextLoader(workspace),
                        mock(ProviderCatalog.class),
                        sessions,
                        new InMemoryTurnStore(sessions),
                        mock(ReActLoop.class),
                        new TurnBudgetRegistry());

        assertThatThrownBy(() ->
                        service.process(new ProcessRequest("cli", "u", "assistant", "  ", "cli")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("消息不能为空");
        assertThat(sessions.findActive(new SessionKey("cli", "u", "assistant"))).isEmpty();
    }

    @Test
    void providerValidationHappensBeforeCreatingSession() throws Exception {
        var workspace = Files.createTempDirectory("agent-service-provider");
        AgentFixtures.agent(workspace, "assistant");
        var sessions = new InMemorySessionManager();
        ProviderCatalog providers = mock(ProviderCatalog.class);
        doThrow(new IllegalArgumentException("provider invalid"))
                .when(providers)
                .validate(org.mockito.ArgumentMatchers.any());
        var service =
                new DefaultAgentService(
                        new AgentLoader(workspace.resolve("agents")),
                        new ContextLoader(workspace),
                        providers,
                        sessions,
                        new InMemoryTurnStore(sessions),
                        mock(ReActLoop.class),
                        new TurnBudgetRegistry());

        assertThatThrownBy(() ->
                        service.process(
                                new ProcessRequest("cli", "u", "assistant", "hello", "cli")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("provider invalid");
        assertThat(sessions.findActive(new SessionKey("cli", "u", "assistant"))).isEmpty();
    }

    @Test
    void terminalSinkFailureDoesNotChangeConfirmedResult() throws Exception {
        var workspace = Files.createTempDirectory("agent-service-sink");
        AgentFixtures.agent(workspace, "assistant");
        ScriptedProviderService provider =
                new ScriptedProviderService()
                        .enqueue(
                                new ChatResponse(
                                        List.of(new Generation(new AssistantMessage("ok")))));
        InMemorySessionManager sessions = new InMemorySessionManager();
        InMemoryTurnStore turns = new InMemoryTurnStore(sessions);
        var service =
                new DefaultAgentService(
                        new AgentLoader(workspace.resolve("agents")),
                        new ContextLoader(workspace),
                        mock(ProviderCatalog.class),
                        sessions,
                        turns,
                        new ReActLoop(
                                provider,
                                new InMemoryMessageStore(),
                                new PromptBuilder(new ContextLoader(workspace)),
                                new TurnGuard(turns)),
                        new TurnBudgetRegistry());

        TurnResult result =
                service.process(
                        new ProcessRequest("cli", "u", "assistant", "hi", "cli"),
                        new TurnSink() {
                            @Override
                            public void onComplete(TurnResult ignored) {
                                throw new IllegalStateException("sink failed");
                            }
                        });

        assertThat(result.status()).isEqualTo(TurnStatus.SUCCEEDED);
        assertThat(result.persistenceStatus()).isEqualTo(PersistenceStatus.CONFIRMED);
    }
}
