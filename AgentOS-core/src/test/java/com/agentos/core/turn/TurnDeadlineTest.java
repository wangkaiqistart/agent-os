package com.agentos.core.turn;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentos.core.provider.ProviderCatalog;
import com.agentos.core.provider.ProviderRequest;
import com.agentos.core.provider.ProviderResult;
import com.agentos.core.support.AgentFixtures;
import com.agentos.core.support.InMemoryMessageStore;
import com.agentos.core.support.InMemorySessionManager;
import com.agentos.core.support.InMemoryTurnStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

class TurnDeadlineTest {
    @Test
    void slowProviderEndsAtSharedDeadlineAndStillConfirmsFinish() throws Exception {
        Path workspace = Files.createTempDirectory("agentos-deadline");
        AgentFixtures.agent(workspace, "a");
        Files.writeString(
                workspace.resolve("agents/a/AGENT.md"),
                "---\nname: a\nprovider:\n  model: test-model\nsettings:\n  turn_timeout: 50ms\n---\n测试\n");
        var sessions = new InMemorySessionManager();
        var turns = new InMemoryTurnStore(sessions);
        var provider =
                new com.agentos.core.provider.ProviderService() {
                    public ProviderResult call(ProviderRequest ignored) {
                        try {
                            Thread.sleep(120);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                        return new ProviderResult(
                                new ChatResponse(
                                        List.of(new Generation(new AssistantMessage("迟到")))),
                                List.of());
                    }

                    public ProviderResult stream(
                            ProviderRequest request, java.util.function.Consumer<String> output) {
                        return call(request);
                    }
                };
        var service =
                new DefaultAgentService(
                        AgentFixtures.loader(workspace),
                        new com.agentos.core.agent.ContextLoader(workspace),
                        Mockito.mock(ProviderCatalog.class),
                        sessions,
                        turns,
                        new ReActLoop(
                                provider,
                                new InMemoryMessageStore(),
                                new PromptBuilder(
                                        new com.agentos.core.agent.ContextLoader(workspace)),
                                new TurnGuard(turns)),
                        new TurnBudgetRegistry());
        long started = System.nanoTime();

        var samples = new java.util.ArrayList<Long>();
        for (int n = 0; n < 20; n++) {
            started = System.nanoTime();
            var result =
                    service.process(
                            new ProcessRequest("cli", "deadline-user-" + n, "a", "你好", "cli"));
            samples.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            assertThat(result.status()).isEqualTo(TurnStatus.TIMED_OUT);
            assertThat(result.persistenceStatus()).isEqualTo(PersistenceStatus.CONFIRMED);
        }
        samples.sort(Long::compareTo);
        assertThat(samples.get(18)).isLessThanOrEqualTo(1050);
        org.slf4j.LoggerFactory.getLogger(getClass())
                .info(
                        "deadline_acceptance scenario=slow_model samples=20 budget_ms=50 p95_ms={}",
                        samples.get(18));
    }

    @Test
    void sustainedStreamingDoesNotRenewDeadlineForTwentyRuns() throws Exception {
        Path workspace = Files.createTempDirectory("agentos-stream-deadline");
        AgentFixtures.agent(workspace, "a");
        Files.writeString(
                workspace.resolve("agents/a/AGENT.md"),
                "---\nname: a\nprovider:\n  model: test-model\nsettings:\n  turn_timeout: 80ms\n---\n测试\n");
        var sessions = new InMemorySessionManager();
        var turns = new InMemoryTurnStore(sessions);
        var provider =
                new com.agentos.core.provider.ProviderService() {
                    public ProviderResult call(ProviderRequest request) {
                        throw new AssertionError("流式路径不应调用同步模型");
                    }

                    public ProviderResult stream(
                            ProviderRequest request, java.util.function.Consumer<String> output) {
                        for (; ; ) {
                            output.accept("delta");
                            try {
                                Thread.sleep(5);
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new TurnDeadlineExceededException();
                            }
                        }
                    }
                };
        try (var service =
                new DefaultAgentService(
                        AgentFixtures.loader(workspace),
                        new com.agentos.core.agent.ContextLoader(workspace),
                        Mockito.mock(ProviderCatalog.class),
                        sessions,
                        turns,
                        new ReActLoop(
                                provider,
                                new InMemoryMessageStore(),
                                new PromptBuilder(
                                        new com.agentos.core.agent.ContextLoader(workspace)),
                                new TurnGuard(turns)),
                        new TurnBudgetRegistry())) {
            var samples = new java.util.ArrayList<Long>();
            for (int n = 0; n < 20; n++) {
                var deltas = new java.util.concurrent.atomic.AtomicInteger();
                var terminal = new java.util.concurrent.atomic.AtomicInteger();
                TurnSink sink =
                        new TurnSink() {
                            public boolean streaming() {
                                return true;
                            }

                            public void onDelta(String text) {
                                deltas.incrementAndGet();
                            }

                            public void onError(TurnResult result) {
                                terminal.incrementAndGet();
                            }

                            public void onComplete(TurnResult result) {
                                terminal.incrementAndGet();
                            }
                        };
                long start = System.nanoTime();
                var result =
                        service.process(
                                new ProcessRequest("cli", "stream-" + n, "a", "hi", "cli"), sink);
                samples.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
                assertThat(result.status()).isEqualTo(TurnStatus.TIMED_OUT);
                assertThat(deltas.get()).isPositive();
                assertThat(terminal).hasValue(1);
            }
            samples.sort(Long::compareTo);
            assertThat(samples.get(18)).isLessThanOrEqualTo(1080);
            org.slf4j.LoggerFactory.getLogger(getClass())
                    .info(
                            "deadline_acceptance scenario=sustained_stream samples=20 budget_ms=80 p95_ms={}",
                            samples.get(18));
        }
    }
}
