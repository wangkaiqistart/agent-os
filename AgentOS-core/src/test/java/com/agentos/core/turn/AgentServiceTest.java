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
    void fullTurnEventSequenceIsWellFormed() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withConfiguration(
                        org.springframework.boot.autoconfigure.AutoConfigurations.of(
                                org.springframework.ai.model.tool.autoconfigure
                                        .ToolCallingAutoConfiguration.class))
                .run(ctx -> {
                    var workspace = Files.createTempDirectory("agent-service-events");
                    AgentFixtures.agent(workspace, "a");
                    Files.writeString(
                            workspace.resolve("agents/a/AGENT.md"),
                            "---\nname: a\nprovider:\n  model: test-model\ntools: [weather]\n---\n助手\n");
                    var provider =
                            new ScriptedProviderService()
                                    .enqueue(
                                            new ChatResponse(
                                                    List.of(
                                                            new Generation(
                                                                    AssistantMessage.builder()
                                                                            .content("先查")
                                                                            .toolCalls(
                                                                                    List.of(
                                                                                            new AssistantMessage
                                                                                                    .ToolCall(
                                                                                                    "id1",
                                                                                                    "function",
                                                                                                    "weather",
                                                                                                    "{}")))
                                                                            .build()))))
                                    .enqueue(
                                            new ChatResponse(
                                                    List.of(
                                                            new Generation(
                                                                    new AssistantMessage("晴")))));
                    org.springframework.ai.tool.ToolCallback weather =
                            org.springframework.ai.tool.function.FunctionToolCallback.builder(
                                            "weather",
                                            (java.util.Map<String, Object> in) -> "sunny")
                                    .description("天气")
                                    .inputType(java.util.Map.class)
                                    .build();
                    var sessions = new InMemorySessionManager();
                    var turns = new InMemoryTurnStore(sessions);
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
                                            new TurnGuard(turns),
                                            new com.agentos.core.tool.ToolCatalog() {
                                                public void validate(
                                                        com.agentos.core.agent.AgentProfile p) {}

                                                public List<
                                                                org.springframework.ai.tool
                                                                        .ToolCallback>
                                                        callbacks(
                                                                com.agentos.core.agent.AgentProfile
                                                                        p) {
                                                    return List.of(weather);
                                                }
                                            },
                                            new ToolExecutor(
                                                    ctx.getBean(
                                                            org.springframework.ai.model.tool
                                                                    .ToolCallingManager.class))),
                                    new TurnBudgetRegistry());
                    var sink = new com.agentos.core.support.RecordingTurnSink();

                    TurnResult result =
                            service.process(new ProcessRequest("cli", "u", "a", "hi", "cli"), sink);

                    assertThat(result.status()).isEqualTo(TurnStatus.SUCCEEDED);
                    sink.assertWellFormed();
                    assertThat(sink.types())
                            .containsExactly(
                                    "run.started",
                                    "step.started",
                                    "message.started",
                                    "message.delta",
                                    "message.ended",
                                    "step.finished",
                                    "tool.started",
                                    "tool.finished",
                                    "step.started",
                                    "message.started",
                                    "message.delta",
                                    "message.ended",
                                    "step.finished",
                                    "run.completed");
                    assertThat(sink.events)
                            .allSatisfy(e -> {
                                assertThat(e.turnId()).isEqualTo(result.turnId());
                                assertThat(e.sessionId()).isNotNull();
                            });
                });
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

    @Test
    void unknownAgentToolFailsBeforeStartingTurnOrCallingModel() throws Exception {
        var workspace = Files.createTempDirectory("agent-service-tools");
        AgentFixtures.agent(workspace, "assistant");
        var sessions = new InMemorySessionManager();
        var turns = new InMemoryTurnStore(sessions);
        var provider = mock(com.agentos.core.provider.ProviderService.class);
        var catalog = mock(com.agentos.core.tool.ToolCatalog.class);
        doThrow(new IllegalArgumentException("agents/assistant/AGENT.md 的 tools 包含未注册工具"))
                .when(catalog)
                .resolve(org.mockito.ArgumentMatchers.any());
        var loop =
                new ReActLoop(
                        provider,
                        new InMemoryMessageStore(),
                        new PromptBuilder(new ContextLoader(workspace)),
                        new TurnGuard(turns),
                        catalog,
                        new ToolExecutor(
                                mock(org.springframework.ai.model.tool.ToolCallingManager.class)));
        var service =
                new DefaultAgentService(
                        new AgentLoader(workspace.resolve("agents")),
                        new ContextLoader(workspace),
                        mock(ProviderCatalog.class),
                        sessions,
                        turns,
                        loop,
                        new TurnBudgetRegistry());
        assertThatThrownBy(() ->
                        service.process(new ProcessRequest("cli", "u", "assistant", "hi", "cli")))
                .hasMessageContaining("AGENT.md")
                .hasMessageContaining("tools");
        assertThat(sessions.findActive(new SessionKey("cli", "u", "assistant"))).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(provider);
    }
}
