package com.agentos.core.turn;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentos.core.agent.LoadedAgent;
import com.agentos.core.provider.ProviderRequest;
import com.agentos.core.provider.ProviderResult;
import com.agentos.core.support.AgentFixtures;
import com.agentos.core.support.InMemoryMessageStore;
import com.agentos.core.support.InMemorySessionManager;
import com.agentos.core.support.InMemoryTurnStore;
import com.agentos.core.tool.ToolCatalog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ReActLoopLimitsTest {
    @TempDir Path workspace;

    @Test
    void stopsAtConfiguredMaxIterationsWithoutEleventhModelCall() throws Exception {
        AgentFixtures.agent(workspace, "a");
        Files.writeString(
                workspace.resolve("agents/a/AGENT.md"),
                "---\nname: a\nprovider:\n  model: test-model\ntools: [weather]\nsettings:\n  max_iterations: 3\n  turn_timeout: 5s\n---\n测试\n");
        LoadedAgent agent = AgentFixtures.loader(workspace).load("a");
        var calls = new AtomicInteger();
        ProviderServiceStub provider = new ProviderServiceStub(calls);
        var result = runLoop(agent, provider);
        assertThat(result.status()).isEqualTo(TurnStatus.MAX_ITERATIONS);
        assertThat(result.iterations()).isEqualTo(3);
        assertThat(result.text()).contains("模型调用上限");
        assertThat(calls).hasValue(3);
    }

    @Test
    void defaultLimitIsTenModelCalls() throws Exception {
        AgentFixtures.agent(workspace, "a");
        LoadedAgent agent = AgentFixtures.loader(workspace).load("a");
        var calls = new AtomicInteger();

        var result = runLoop(agent, new ProviderServiceStub(calls));

        assertThat(result.status()).isEqualTo(TurnStatus.MAX_ITERATIONS);
        assertThat(result.iterations()).isEqualTo(10);
        assertThat(calls).hasValue(10);
    }

    @Test
    void completedResponseAtLimitIsSuccessWithoutExtraSummaryCall() throws Exception {
        AgentFixtures.agent(workspace, "a");
        Files.writeString(
                workspace.resolve("agents/a/AGENT.md"),
                "---\nname: a\nprovider:\n  model: test-model\nsettings:\n  max_iterations: 1\n  turn_timeout: 5s\n---\n测试\n");
        LoadedAgent agent = AgentFixtures.loader(workspace).load("a");
        var calls = new AtomicInteger();

        var result = runLoop(agent, new ProviderServiceStub(calls, true));

        assertThat(result.status()).isEqualTo(TurnStatus.SUCCEEDED);
        assertThat(result.iterations()).isEqualTo(1);
        assertThat(result.text()).isEqualTo("完成");
        assertThat(calls).hasValue(1);
    }

    private ReActLoop.Result runLoop(LoadedAgent agent, ProviderServiceStub provider) {
        var sessions = new InMemorySessionManager();
        var turns = new InMemoryTurnStore(sessions);
        var budget = TurnExecutionBudget.start(Duration.ofSeconds(5));
        var turn =
                turns.startTurn(
                        java.util.UUID.randomUUID(),
                        new com.agentos.core.session.SessionKey("cli", "u", "a"),
                        "cli",
                        "天气",
                        budget);
        ToolCallback weather =
                new ToolCallback() {
                    public ToolDefinition getToolDefinition() {
                        return ToolDefinition.builder()
                                .name("weather")
                                .description("天气")
                                .inputSchema("{}")
                                .build();
                    }

                    public String call(String input) {
                        return "sunny";
                    }
                };
        ToolCatalog catalog =
                new ToolCatalog() {
                    public void validate(com.agentos.core.agent.AgentProfile ignored) {}

                    public List<ToolCallback> callbacks(
                            com.agentos.core.agent.AgentProfile ignored) {
                        return List.of(weather);
                    }
                };
        var result = new AtomicReference<ReActLoop.Result>();
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ToolCallingAutoConfiguration.class))
                .run(context -> {
                    var loop =
                            new ReActLoop(
                                    provider,
                                    new InMemoryMessageStore(),
                                    new PromptBuilder(
                                            new com.agentos.core.agent.ContextLoader(workspace)),
                                    new TurnGuard(turns),
                                    catalog,
                                    new ToolExecutor(context.getBean(ToolCallingManager.class)));
                    result.set(loop.run(turn, agent, "天气", budget, TurnSink.NOOP, "cli"));
                });
        return result.get();
    }

    private static final class ProviderServiceStub
            implements com.agentos.core.provider.ProviderService {
        private final AtomicInteger calls;
        private final boolean completed;

        ProviderServiceStub(AtomicInteger calls) {
            this(calls, false);
        }

        ProviderServiceStub(AtomicInteger calls, boolean completed) {
            this.calls = calls;
            this.completed = completed;
        }

        public ProviderResult call(ProviderRequest request) {
            int id = calls.incrementAndGet();
            if (completed) {
                return new ProviderResult(
                        new ChatResponse(List.of(new Generation(new AssistantMessage("完成")))),
                        List.of());
            }
            return new ProviderResult(
                    new ChatResponse(
                            List.of(
                                    new Generation(
                                            AssistantMessage.builder()
                                                    .content("")
                                                    .toolCalls(
                                                            List.of(
                                                                    new AssistantMessage.ToolCall(
                                                                            "id-" + id,
                                                                            "function",
                                                                            "weather",
                                                                            "{}")))
                                                    .build()))),
                    List.of());
        }

        public ProviderResult stream(
                ProviderRequest request, java.util.function.Consumer<String> output) {
            return call(request);
        }
    }
}
