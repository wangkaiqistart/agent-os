package com.agentos.core.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.agentos.core.agent.AgentProfile;
import com.agentos.core.agent.ContextLoader;
import com.agentos.core.provider.ProviderCallException;
import com.agentos.core.provider.ProviderCatalog;
import com.agentos.core.provider.ProviderRequest;
import com.agentos.core.provider.ProviderResult;
import com.agentos.core.provider.ProviderService;
import com.agentos.core.support.AgentFixtures;
import com.agentos.core.support.InMemoryMessageStore;
import com.agentos.core.support.InMemorySessionManager;
import com.agentos.core.support.InMemoryTurnStore;
import com.agentos.core.support.RecordingTurnSink;
import com.agentos.core.tool.ToolCatalog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 用户取消：2 秒内以 CANCELLED 结束，之后不再调用模型或工具；未知轮次返回 false。 */
class AgentServiceCancelTest {
    @TempDir Path workspace;

    private final AtomicInteger modelCalls = new AtomicInteger();
    private final AtomicInteger toolCalls = new AtomicInteger();
    private InMemoryTurnStore turns;

    /** 第一次调用：streamSlow 为真时持续输出直到被中断，否则请求一个工具；之后的调用直接回答。 */
    private ProviderService provider(boolean streamSlow) {
        return new ProviderService() {
            public ProviderResult call(ProviderRequest request) {
                return next(request, null);
            }

            public ProviderResult stream(ProviderRequest request, Consumer<String> out) {
                return next(request, out);
            }

            private ProviderResult next(ProviderRequest request, Consumer<String> out) {
                int n = modelCalls.incrementAndGet();
                if (n == 1 && streamSlow) {
                    try {
                        for (; ; ) {
                            out.accept("字");
                            Thread.sleep(20);
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new ProviderCallException("模型调用失败：CANCELLED", List.of());
                    }
                }
                var message =
                        n == 1
                                ? AssistantMessage.builder()
                                        .content("")
                                        .toolCalls(
                                                List.of(
                                                        new AssistantMessage.ToolCall(
                                                                "slow-1",
                                                                "function",
                                                                "slow",
                                                                "{}")))
                                        .build()
                                : new AssistantMessage("不应到达");
                return new ProviderResult(
                        new ChatResponse(List.of(new Generation(message))), List.of());
            }
        };
    }

    /** 慢工具：sideEffect 为真时先登记一次未确认的提交，模拟有副作用的动作已经发出。 */
    private ToolCallback slowTool(boolean sideEffect) {
        return new ToolCallback() {
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name("slow")
                        .description("慢工具")
                        .inputSchema("{\"type\":\"object\"}")
                        .build();
            }

            public String call(String input) {
                throw new AssertionError("需要 ToolContext");
            }

            public String call(String input, ToolContext context) {
                toolCalls.incrementAndGet();
                if (sideEffect) {
                    ((TurnExecutionBudget) context.getContext().get(ToolContextKeys.TURN_BUDGET))
                            .beginSubmission();
                }
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return "done";
            }
        };
    }

    private DefaultAgentService service(
            ProviderService provider, ToolCallingManager manager, ToolCallback tool)
            throws Exception {
        AgentFixtures.agent(workspace, "a");
        Files.writeString(
                workspace.resolve("agents/a/AGENT.md"),
                "---\nname: a\nprovider:\n  model: test-model\ntools: [slow]\nsettings:\n  turn_timeout: 30s\n---\n助手\n");
        var sessions = new InMemorySessionManager();
        turns = new InMemoryTurnStore(sessions);
        ToolCatalog catalog =
                new ToolCatalog() {
                    public void validate(AgentProfile p) {}

                    public List<ToolCallback> callbacks(AgentProfile p) {
                        return tool == null ? List.of() : List.of(tool);
                    }
                };
        return new DefaultAgentService(
                AgentFixtures.loader(workspace),
                new ContextLoader(workspace),
                mock(ProviderCatalog.class),
                sessions,
                turns,
                new ReActLoop(
                        provider,
                        new InMemoryMessageStore(),
                        new PromptBuilder(new ContextLoader(workspace)),
                        new TurnGuard(turns),
                        catalog,
                        manager == null ? null : new ToolExecutor(manager)),
                new TurnBudgetRegistry());
    }

    /** 等收到轮次开始与 delayMillis 后，从另一个线程取消。 */
    private static void cancelLater(
            DefaultAgentService service,
            RecordingTurnSink sink,
            long delayMillis,
            boolean[] accepted) {
        Thread.ofVirtual()
                .start(() -> {
                    try {
                        while (sink.events.isEmpty()) {
                            Thread.sleep(5);
                        }
                        Thread.sleep(delayMillis);
                        UUID turnId = sink.events.getFirst().turnId();
                        accepted[0] = service.cancel(turnId);
                        // 重复取消结果不变。
                        accepted[1] = service.cancel(turnId);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
    }

    private TurnResult runAndCancel(
            DefaultAgentService service, RecordingTurnSink sink, boolean[] accepted) {
        cancelLater(service, sink, 200, accepted);
        long start = System.nanoTime();
        TurnResult result = service.process(new ProcessRequest("cli", "u", "a", "hi", "cli"), sink);
        long cancelToEnd = Duration.ofNanos(System.nanoTime() - start).toMillis() - 200;
        assertThat(cancelToEnd).as("取消后结束耗时 ms").isLessThan(2000);
        return result;
    }

    @Test
    void cancelDuringStreamingEndsCancelledWithoutFurtherCalls() throws Exception {
        var service = service(provider(true), null, null);
        var sink = new RecordingTurnSink(true);
        boolean[] accepted = new boolean[2];

        TurnResult result = runAndCancel(service, sink, accepted);

        assertThat(accepted).containsExactly(true, true);
        assertThat(result.status()).isEqualTo(TurnStatus.CANCELLED);
        assertThat(result.failureCode()).isNull();
        assertThat(result.text()).isEqualTo("本轮已取消");
        assertThat(modelCalls).hasValue(1);
        assertThat(turns.result(result.turnId()).status()).isEqualTo("CANCELLED");
        sink.assertWellFormed();
        assertThat(sink.events.getLast().payload())
                .isInstanceOfSatisfying(
                        TurnEventPayload.RunCancelled.class,
                        cancelled -> assertThat(cancelled.possiblyExecuted()).isFalse());
        assertThat(service.cancel(result.turnId())).as("已结束的轮次").isFalse();
    }

    @Test
    void cancelDuringReadOnlyToolStopsItAndCallsNothingElse() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ToolCallingAutoConfiguration.class))
                .run(ctx -> {
                    var service =
                            service(
                                    provider(false),
                                    ctx.getBean(ToolCallingManager.class),
                                    slowTool(false));
                    var sink = new RecordingTurnSink(true);
                    boolean[] accepted = new boolean[2];

                    TurnResult result = runAndCancel(service, sink, accepted);

                    assertThat(result.status()).isEqualTo(TurnStatus.CANCELLED);
                    assertThat(modelCalls).as("取消后不再调用模型").hasValue(1);
                    assertThat(toolCalls).hasValue(1);
                    sink.assertWellFormed();
                });
    }

    @Test
    void cancelDuringSideEffectToolSaysItMayHaveTakenEffect() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ToolCallingAutoConfiguration.class))
                .run(ctx -> {
                    var service =
                            service(
                                    provider(false),
                                    ctx.getBean(ToolCallingManager.class),
                                    slowTool(true));
                    var sink = new RecordingTurnSink(true);
                    boolean[] accepted = new boolean[2];

                    TurnResult result = runAndCancel(service, sink, accepted);

                    assertThat(result.status()).isEqualTo(TurnStatus.CANCELLED);
                    assertThat(result.text()).contains("提交可能已生效");
                    assertThat(sink.events.getLast().payload())
                            .isInstanceOfSatisfying(
                                    TurnEventPayload.RunCancelled.class,
                                    cancelled -> assertThat(cancelled.possiblyExecuted()).isTrue());
                });
    }

    @Test
    void unknownTurnIsNotCancelled() throws Exception {
        var service = service(provider(false), null, null);
        assertThat(service.cancel(UUID.randomUUID())).isFalse();
        assertThat(service.cancel(null)).isFalse();
    }
}
