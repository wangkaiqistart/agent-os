package com.agentos.core.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.core.agent.AgentProfile;
import com.agentos.core.agent.ContextLoader;
import com.agentos.core.agent.LoadedAgent;
import com.agentos.core.provider.CallMode;
import com.agentos.core.provider.FailureKind;
import com.agentos.core.provider.LlmCallRecord;
import com.agentos.core.provider.ProviderCallException;
import com.agentos.core.provider.ProviderRequest;
import com.agentos.core.provider.ProviderResult;
import com.agentos.core.provider.ProviderService;
import com.agentos.core.session.SessionKey;
import com.agentos.core.session.TurnSnapshot;
import com.agentos.core.support.AgentFixtures;
import com.agentos.core.support.InMemoryMessageStore;
import com.agentos.core.support.InMemorySessionManager;
import com.agentos.core.support.InMemoryTurnStore;
import com.agentos.core.support.RecordingTurnSink;
import com.agentos.core.tool.ToolCatalog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
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

/** 直接运行循环时的步骤、消息、工具事件（没有轮次开始与终态，那两类由 AgentService 发出）。 */
class ReActLoopEventsTest {
    @TempDir Path workspace;

    private final InMemorySessionManager sessions = new InMemorySessionManager();
    private final InMemoryTurnStore turns = new InMemoryTurnStore(sessions);

    /** 每次调用按脚本返回：文字分段 + 可选工具调用；记录的调用数可多于 1 以模拟首段前的重试。 */
    private static final class Scripted implements ProviderService {
        private final Deque<Step> steps = new ArrayDeque<>();

        record Step(List<String> chunks, boolean tool, int attempts) {}

        Scripted then(List<String> chunks, boolean tool, int attempts) {
            steps.add(new Step(chunks, tool, attempts));
            return this;
        }

        @Override
        public ProviderResult call(ProviderRequest request) {
            return next(null);
        }

        @Override
        public ProviderResult stream(ProviderRequest request, Consumer<String> sink) {
            return next(sink);
        }

        private ProviderResult next(Consumer<String> sink) {
            Step step = steps.remove();
            if (sink != null) {
                step.chunks().forEach(sink);
            }
            String text = String.join("", step.chunks());
            var message =
                    AssistantMessage.builder()
                            .content(text.isEmpty() ? null : text)
                            .toolCalls(
                                    step.tool()
                                            ? List.of(
                                                    new AssistantMessage.ToolCall(
                                                            "call-a", "function", "weather", "{}"),
                                                    new AssistantMessage.ToolCall(
                                                            "call-b", "function", "weather", "{}"))
                                            : List.of())
                            .build();
            var records =
                    java.util.stream.IntStream.rangeClosed(1, step.attempts())
                            .mapToObj(attempt -> record(attempt, attempt == step.attempts()))
                            .toList();
            return new ProviderResult(new ChatResponse(List.of(new Generation(message))), records);
        }
    }

    private static LlmCallRecord record(int attempt, boolean success) {
        return new LlmCallRecord(
                null,
                null,
                1,
                "primary",
                "test-model",
                success ? "actual-model" : null,
                null,
                CallMode.STREAM,
                attempt,
                success ? "stop" : null,
                0,
                0,
                success ? 7 : null,
                success ? 3 : null,
                success ? 10 : null,
                null,
                null,
                null,
                null,
                null,
                success,
                success ? null : FailureKind.RATE_LIMITED,
                success ? null : 429,
                null,
                Instant.now(),
                1);
    }

    private ReActLoop loop(ProviderService provider, ToolCallingManager manager) {
        ToolCallback weather =
                new ToolCallback() {
                    public ToolDefinition getToolDefinition() {
                        return ToolDefinition.builder()
                                .name("weather")
                                .description("天气")
                                .inputSchema("{\"type\":\"object\"}")
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
                        return List.of(weather);
                    }
                };
        return new ReActLoop(
                provider,
                new InMemoryMessageStore(),
                new PromptBuilder(new ContextLoader(workspace)),
                new TurnGuard(turns),
                catalog,
                manager == null ? null : new ToolExecutor(manager));
    }

    private LoadedAgent agent() throws Exception {
        AgentFixtures.agent(workspace, "a");
        Files.writeString(
                workspace.resolve("agents/a/AGENT.md"),
                "---\nname: a\nprovider:\n  model: test-model\ntools: [weather]\n---\n助手\n");
        return AgentFixtures.loader(workspace).load("a");
    }

    private TurnSnapshot turn() {
        return turns.startTurn(
                UUID.randomUUID(),
                new SessionKey("cli", "u-" + UUID.randomUUID(), "a"),
                "cli",
                "hi",
                TurnExecutionBudget.start(Duration.ofSeconds(5)));
    }

    private static RecordingTurnSink sink(boolean streaming) {
        return new RecordingTurnSink(streaming);
    }

    private void run(ReActLoop loop, RecordingTurnSink sink) throws Exception {
        loop.run(turn(), agent(), "hi", TurnExecutionBudget.start(Duration.ofSeconds(5)), sink);
    }

    @Test
    void plainStreamedAnswerHasOneMessageWithChunkDeltas() throws Exception {
        var sink = sink(true);
        run(loop(new Scripted().then(List.of("你", "", "好"), false, 1), null), sink);

        sink.assertBodyWellFormed();
        assertThat(sink.types())
                .containsExactly(
                        "step.started",
                        "message.started",
                        "message.delta",
                        "message.delta",
                        "message.ended",
                        "step.finished");
        var finished = (TurnEventPayload.StepFinished) sink.events.getLast().payload();
        assertThat(finished.outcome()).isEqualTo(TurnEventPayload.StepOutcome.SUCCEEDED);
        assertThat(finished.finishReason()).isEqualTo("stop");
        assertThat(finished.requestedModel()).isEqualTo("test-model");
        assertThat(finished.actualModel()).isEqualTo("actual-model");
        assertThat(finished.promptTokens()).isEqualTo(7);
        assertThat(finished.completionTokens()).isEqualTo(3);
    }

    @Test
    void synchronousAnswerSendsWholeTextAsOneDelta() throws Exception {
        var sink = sink(false);
        run(loop(new Scripted().then(List.of("你", "好"), false, 1), null), sink);

        sink.assertBodyWellFormed();
        assertThat(sink.events)
                .filteredOn(e -> e.payload() instanceof TurnEventPayload.MessageDelta)
                .singleElement()
                .satisfies(e ->
                        assertThat(((TurnEventPayload.MessageDelta) e.payload()).text())
                                .isEqualTo("你好"));
    }

    @Test
    void textThenTwoToolsThenAnswerKeepsToolsBetweenSteps() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ToolCallingAutoConfiguration.class))
                .run(ctx -> {
                    var sink = sink(true);
                    var provider =
                            new Scripted()
                                    .then(List.of("先查"), true, 1)
                                    .then(List.of("晴"), false, 1);
                    run(loop(provider, ctx.getBean(ToolCallingManager.class)), sink);

                    sink.assertBodyWellFormed();
                    assertThat(sink.types())
                            .containsExactly(
                                    "step.started",
                                    "message.started",
                                    "message.delta",
                                    "message.ended",
                                    "step.finished",
                                    "tool.started",
                                    "tool.finished",
                                    "tool.started",
                                    "tool.finished",
                                    "step.started",
                                    "message.started",
                                    "message.delta",
                                    "message.ended",
                                    "step.finished");
                    assertThat(sink.events)
                            .filteredOn(e -> e.payload() instanceof TurnEventPayload.ToolStarted)
                            .extracting(
                                    e -> ((TurnEventPayload.ToolStarted) e.payload()).toolCallId())
                            .containsExactly("call-a", "call-b");
                });
    }

    @Test
    void toolOnlyResponseHasNoMessageEvents() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ToolCallingAutoConfiguration.class))
                .run(ctx -> {
                    var sink = sink(true);
                    var provider =
                            new Scripted().then(List.of(), true, 1).then(List.of("完成"), false, 1);
                    run(loop(provider, ctx.getBean(ToolCallingManager.class)), sink);

                    sink.assertBodyWellFormed();
                    assertThat(sink.types().subList(0, 2))
                            .containsExactly("step.started", "step.finished");
                });
    }

    @Test
    void retryBeforeFirstTextStillYieldsOneMessage() throws Exception {
        var sink = sink(true);
        run(loop(new Scripted().then(List.of("答"), false, 2), null), sink);

        sink.assertBodyWellFormed();
        assertThat(sink.types()).filteredOn("message.started"::equals).hasSize(1);
    }

    @Test
    void providerFailureFinishesStepWithFailureKind() throws Exception {
        var sink = sink(true);
        ProviderService failing =
                new ProviderService() {
                    public ProviderResult call(ProviderRequest request) {
                        throw new AssertionError("应走流式");
                    }

                    public ProviderResult stream(ProviderRequest request, Consumer<String> out) {
                        throw new ProviderCallException(
                                "模型调用失败", List.of(record(1, false), record(2, false)));
                    }
                };
        assertThatThrownBy(() -> run(loop(failing, null), sink))
                .isInstanceOf(ProviderCallException.class);

        sink.assertBodyWellFormed();
        assertThat(sink.types()).containsExactly("step.started", "step.finished");
        var finished = (TurnEventPayload.StepFinished) sink.events.getLast().payload();
        assertThat(finished.outcome()).isEqualTo(TurnEventPayload.StepOutcome.FAILED);
        assertThat(finished.failureKind()).isEqualTo(FailureKind.RATE_LIMITED);
    }
}
