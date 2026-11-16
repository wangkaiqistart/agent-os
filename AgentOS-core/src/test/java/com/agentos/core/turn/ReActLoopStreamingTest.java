package com.agentos.core.turn;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentos.core.agent.AgentProfile;
import com.agentos.core.agent.ContextLoader;
import com.agentos.core.provider.ProviderCatalog;
import com.agentos.core.provider.ProviderRequest;
import com.agentos.core.provider.ProviderResult;
import com.agentos.core.provider.ProviderService;
import com.agentos.core.support.AgentFixtures;
import com.agentos.core.support.InMemoryMessageStore;
import com.agentos.core.support.InMemorySessionManager;
import com.agentos.core.support.InMemoryTurnStore;
import com.agentos.core.tool.ToolCatalog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ReActLoopStreamingTest {
    @TempDir Path workspace;
    private final List<String> events = new CopyOnWriteArrayList<>();
    private final List<ProviderRequest> requests = new CopyOnWriteArrayList<>();

    private AgentService service(
            ProviderService provider, ToolCallingManager manager, String timeout) throws Exception {
        return service(provider, manager, timeout, false);
    }

    private AgentService service(
            ProviderService provider, ToolCallingManager manager, String timeout, boolean direct)
            throws Exception {
        AgentFixtures.agent(workspace, "a");
        Files.writeString(
                workspace.resolve("agents/a/AGENT.md"),
                "---\nname: a\nprovider:\n  model: test-model\ntools: [weather]\nsettings:\n  turn_timeout: "
                        + timeout
                        + "\n---\n助手\n");
        var sessions = new InMemorySessionManager();
        var turns = new InMemoryTurnStore(sessions);
        ToolCallback callback =
                new ToolCallback() {
                    public ToolDefinition getToolDefinition() {
                        return ToolDefinition.builder()
                                .name("weather")
                                .description("天气")
                                .inputSchema("{\"type\":\"object\"}")
                                .build();
                    }

                    public org.springframework.ai.tool.metadata.ToolMetadata getToolMetadata() {
                        return org.springframework.ai.tool.metadata.ToolMetadata.builder()
                                .returnDirect(direct)
                                .build();
                    }

                    public String call(String input) {
                        return "sunny";
                    }
                };
        ToolCatalog catalog =
                new ToolCatalog() {
                    public void validate(AgentProfile p) {}

                    public List<ToolCallback> callbacks(AgentProfile p) {
                        return List.of(callback);
                    }
                };
        var loop =
                new ReActLoop(
                        provider,
                        new InMemoryMessageStore(),
                        new PromptBuilder(new ContextLoader(workspace)),
                        new TurnGuard(turns),
                        catalog,
                        new ToolExecutor(manager));
        return new DefaultAgentService(
                AgentFixtures.loader(workspace),
                new ContextLoader(workspace),
                org.mockito.Mockito.mock(ProviderCatalog.class),
                sessions,
                turns,
                loop,
                new TurnBudgetRegistry());
    }

    private ProviderService provider(boolean tool) {
        var calls = new AtomicInteger();
        return new ProviderService() {
            public ProviderResult call(ProviderRequest r) {
                return next(r, null);
            }

            public ProviderResult stream(ProviderRequest r, Consumer<String> out) {
                return next(r, out);
            }

            private ProviderResult next(ProviderRequest r, Consumer<String> out) {
                requests.add(r);
                int n = calls.incrementAndGet();
                boolean action = tool && n == 1;
                if (out != null) {
                    out.accept(action ? "thinking" : "answer");
                }
                var message =
                        AssistantMessage.builder()
                                .content(action ? "thinking" : "answer")
                                .toolCalls(
                                        action
                                                ? List.of(
                                                        new AssistantMessage.ToolCall(
                                                                "id1", "function", "weather", "{}"))
                                                : List.of())
                                .build();
                return new ProviderResult(
                        new ChatResponse(List.of(new Generation(message))), List.of());
            }
        };
    }

    @Test
    void streamingToolRoundPreservesFiveEventTypesAndFullHistory() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ToolCallingAutoConfiguration.class))
                .run(ctx -> {
                    var service =
                            service(provider(true), ctx.getBean(ToolCallingManager.class), "5s");
                    var result =
                            service.process(
                                    new ProcessRequest("cli", "u", "a", "hi", "cli"),
                                    new TurnSink() {
                                        public boolean streaming() {
                                            return true;
                                        }

                                        public void onDelta(String s) {
                                            events.add("delta:" + s);
                                        }

                                        public void onToolCall(String id, String n, String a) {
                                            events.add("call:" + id);
                                        }

                                        public void onToolResult(String id, String n, String r) {
                                            events.add("result:" + id);
                                        }

                                        public void onComplete(TurnResult r) {
                                            events.add("complete");
                                        }

                                        public void onError(TurnResult r) {
                                            events.add("error");
                                        }
                                    });
                    assertThat(result.status()).isEqualTo(TurnStatus.SUCCEEDED);
                    assertThat(result.iterations()).isEqualTo(2);
                    assertThat(events)
                            .containsExactly(
                                    "delta:thinking",
                                    "call:id1",
                                    "result:id1",
                                    "delta:answer",
                                    "complete");
                    assertThat(requests).hasSize(2);
                    assertThat(requests.getFirst().toolContext())
                            .containsKey(ToolContextKeys.TURN_CONTEXT)
                            .doesNotContainKey(ToolContextKeys.TOOL_CALL_LEDGER);
                    assertThat(requests.get(1).messages()).hasSize(4);
                    assertThat(
                                    ((ToolResponseMessage) requests.get(1).messages().getLast())
                                            .getResponses()
                                            .getFirst()
                                            .responseData())
                            .isEqualTo("sunny");
                });
    }

    @Test
    void returnDirectStopsAfterOneModelCall() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ToolCallingAutoConfiguration.class))
                .run(ctx -> {
                    var service =
                            service(
                                    provider(true),
                                    ctx.getBean(ToolCallingManager.class),
                                    "5s",
                                    true);
                    var result =
                            service.process(
                                    new ProcessRequest("cli", "direct-user", "a", "hi", "cli"));
                    assertThat(result.status()).isEqualTo(TurnStatus.SUCCEEDED);
                    assertThat(result.text()).isEqualTo("sunny");
                    assertThat(result.iterations()).isOne();
                    assertThat(requests).hasSize(1);
                });
    }

    @Test
    void brokenProcessSinkStopsCallbacksAndDoesNotCallTerminalSinkAgain() throws Exception {
        var service =
                service(provider(false), org.mockito.Mockito.mock(ToolCallingManager.class), "2s");
        var result =
                service.process(
                        new ProcessRequest("cli", "u", "a", "hi", "cli"),
                        new TurnSink() {
                            public boolean streaming() {
                                return true;
                            }

                            public void onDelta(String s) {
                                events.add("delta");
                                throw new IllegalStateException("synthetic-sink-error");
                            }

                            public void onError(TurnResult r) {
                                events.add("error");
                            }

                            public void onComplete(TurnResult r) {
                                events.add("complete");
                            }
                        });
        assertThat(events).containsExactly("delta");
        assertThat(result.status()).isEqualTo(TurnStatus.INTERRUPTED);
    }

    @Test
    void terminalThrowAndBlockDoNotChangeConfirmedResultOrSendAnotherEvent() throws Exception {
        for (boolean block : List.of(false, true)) {
            events.clear();
            var release = new CountDownLatch(1);
            var service =
                    service(
                            provider(false),
                            org.mockito.Mockito.mock(ToolCallingManager.class),
                            "150ms");
            long start = System.nanoTime();
            try {
                var result =
                        service.process(
                                new ProcessRequest(
                                        "cli", UUID.randomUUID().toString(), "a", "hi", "cli"),
                                new TurnSink() {
                                    public void onComplete(TurnResult r) {
                                        events.add("complete");
                                        if (block) {
                                            awaitIgnoringInterrupt(release);
                                        }
                                        throw new IllegalStateException("terminal");
                                    }

                                    public void onError(TurnResult r) {
                                        events.add("error");
                                    }
                                });
                assertThat(result.status()).isEqualTo(TurnStatus.SUCCEEDED);
                assertThat(result.persistenceStatus()).isEqualTo(PersistenceStatus.CONFIRMED);
                assertThat(events).containsExactly("complete");
                assertThat(System.nanoTime() - start)
                        .isLessThan(TimeUnit.MILLISECONDS.toNanos(1250));
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void blockedProcessSinkNeverReentersSinkAndSkipsTerminalAfterHardStop() throws Exception {
        var release = new CountDownLatch(1);
        var service =
                service(
                        provider(false),
                        org.mockito.Mockito.mock(ToolCallingManager.class),
                        "100ms");
        long start = System.nanoTime();
        try {
            var result =
                    service.process(
                            new ProcessRequest("cli", "u", "a", "hi", "cli"),
                            new TurnSink() {
                                public boolean streaming() {
                                    return true;
                                }

                                public void onDelta(String s) {
                                    events.add("delta");
                                    awaitIgnoringInterrupt(release);
                                }

                                public void onComplete(TurnResult r) {
                                    events.add("complete");
                                }

                                public void onError(TurnResult r) {
                                    events.add("error");
                                }
                            });
            assertThat(events).containsExactly("delta");
            assertThat(result.status()).isEqualTo(TurnStatus.TIMED_OUT);
            assertThat(requests).hasSize(1);
            assertThat(System.nanoTime() - start).isLessThan(TimeUnit.MILLISECONDS.toNanos(1200));
        } finally {
            release.countDown();
        }
    }

    @Test
    void ordinaryFailureSendsExactlyOneErrorAndTerminalFailureCannotChangeIt() throws Exception {
        for (boolean block : List.of(false, true)) {
            events.clear();
            var release = new CountDownLatch(1);
            ProviderService empty =
                    new ProviderService() {
                        public ProviderResult call(ProviderRequest r) {
                            return new ProviderResult(
                                    new ChatResponse(
                                            List.of(new Generation(new AssistantMessage(" ")))),
                                    List.of());
                        }

                        public ProviderResult stream(ProviderRequest r, Consumer<String> out) {
                            out.accept("thinking");
                            return call(r);
                        }
                    };
            var service =
                    service(empty, org.mockito.Mockito.mock(ToolCallingManager.class), "150ms");
            long start = System.nanoTime();
            try {
                var result =
                        service.process(
                                new ProcessRequest(
                                        "cli", UUID.randomUUID().toString(), "a", "hi", "cli"),
                                new TurnSink() {
                                    public boolean streaming() {
                                        return true;
                                    }

                                    public void onDelta(String text) {
                                        events.add("delta");
                                    }

                                    public void onComplete(TurnResult r) {
                                        events.add("complete");
                                    }

                                    public void onError(TurnResult r) {
                                        events.add("error");
                                        if (block) {
                                            awaitIgnoringInterrupt(release);
                                        }
                                        throw new IllegalStateException("terminal failure");
                                    }
                                });
                assertThat(result.status()).isEqualTo(TurnStatus.FAILED);
                assertThat(result.persistenceStatus()).isEqualTo(PersistenceStatus.CONFIRMED);
                assertThat(events).containsExactly("delta", "error");
                assertThat(System.nanoTime() - start)
                        .isLessThan(TimeUnit.MILLISECONDS.toNanos(1250));
            } finally {
                release.countDown();
            }
        }
    }

    private static void awaitIgnoringInterrupt(CountDownLatch latch) {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < end) {
            try {
                latch.await(Math.max(1, end - System.nanoTime()), TimeUnit.NANOSECONDS);
                return;
            } catch (InterruptedException ignored) {
                // 模拟不合作的输出端；自身最多阻塞两秒，测试 finally 也会主动放行。
            }
        }
    }
}
