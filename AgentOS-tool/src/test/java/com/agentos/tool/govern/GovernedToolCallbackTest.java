package com.agentos.tool.govern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentos.core.agent.AgentProfile;
import com.agentos.core.agent.LoadedAgent;
import com.agentos.core.audit.AuditWriteException;
import com.agentos.core.audit.ToolInvocationRecord;
import com.agentos.core.audit.ToolInvocationRecorder;
import com.agentos.core.provider.Profile;
import com.agentos.core.tool.Redactor;
import com.agentos.core.tool.SandboxViolationException;
import com.agentos.core.tool.ToolPolicy;
import com.agentos.core.turn.LeaseLostException;
import com.agentos.core.turn.ToolCallLedger;
import com.agentos.core.turn.ToolContextKeys;
import com.agentos.core.turn.TurnContext;
import com.agentos.core.turn.TurnDeadlineExceededException;
import com.agentos.core.turn.TurnExecutionBudget;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;

class GovernedToolCallbackTest {
    static final ToolDefinition DEF =
            ToolDefinition.builder()
                    .name("http_get")
                    .description("读取")
                    .inputSchema("{\"type\":\"object\"}")
                    .build();
    static final Redactor REDACTOR = new Redactor(() -> List.of("KNOWN_SECRET"));

    static ToolContext context(Duration duration, Runnable checkpoint) {
        var budget = TurnExecutionBudget.start(duration);
        var agent =
                new LoadedAgent(
                        new AgentProfile(
                                "a",
                                "",
                                new Profile(null, "m", null, null, null),
                                List.of("http_get"),
                                null),
                        "",
                        Map.of());
        var turn =
                new TurnContext(
                        UUID.randomUUID(), UUID.randomUUID(), agent, "test", 2, budget.deadline());
        return new ToolContext(
                Map.of(
                        ToolContextKeys.TURN_CONTEXT,
                        turn,
                        ToolContextKeys.TURN_BUDGET,
                        budget,
                        ToolContextKeys.TOOL_CALL_LEDGER,
                        new ToolCallLedger(
                                List.of(
                                        new AssistantMessage.ToolCall(
                                                "id", "function", "http_get", "{}")),
                                checkpoint)));
    }

    static ToolCallback callback(java.util.function.Function<ToolContext, String> fn) {
        return new ToolCallback() {
            public ToolDefinition getToolDefinition() {
                return DEF;
            }

            public String call(String s) {
                return fn.apply(null);
            }

            public String call(String s, ToolContext c) {
                return fn.apply(c);
            }
        };
    }

    private GovernedToolCallback govern(
            ToolCallback delegate, ToolInvocationRecorder recorder, Duration timeout) {
        return new GovernedToolCallback(
                delegate, new ToolPolicy(true, timeout, 10000), recorder, REDACTOR);
    }

    @Test
    void beginPrecedesExecutionOnIndependentVirtualThreadWithExplicitMdcAndRedactedResult() {
        var records = new ArrayList<ToolInvocationRecord>();
        var caller = Thread.currentThread();
        var worker = new AtomicReference<Thread>();
        var recorder = mock(ToolInvocationRecorder.class);
        when(recorder.begin(any()))
                .thenAnswer(i -> {
                    records.add(i.getArgument(0));
                    return i.getArgument(0);
                });
        doAnswer(
                        i -> {
                            records.add(i.getArgument(0));
                            return null;
                        })
                .when(recorder)
                .complete(any());
        var tool =
                govern(
                        callback(c -> {
                            assertThat(records).hasSize(1);
                            assertThat(Thread.currentThread()).isNotSameAs(caller);
                            assertThat(Thread.currentThread().isVirtual()).isTrue();
                            assertThat(MDC.getCopyOfContextMap())
                                    .containsEntry("agent", "a")
                                    .containsEntry("iteration", "2");
                            worker.set(Thread.currentThread());
                            return "KNOWN_SECRET" + "x".repeat(10000);
                        }),
                        recorder,
                        Duration.ofSeconds(2));
        String result =
                tool.call(
                        "{\"token\":\"INPUT_SECRET\",\"nested\":{\"x\":1}}",
                        context(Duration.ofSeconds(3), () -> {}));
        assertThat(result).doesNotContain("KNOWN_SECRET").contains("原长 10012");
        assertThat(records).hasSize(2);
        var begin = records.getFirst();
        var end = records.getLast();
        assertThat(begin.status()).isEqualTo("in_progress");
        assertThat(begin.attempts()).isZero();
        assertThat(begin.inputJson()).doesNotContain("INPUT_SECRET").contains("nested");
        assertThat(end.status()).isEqualTo("success");
        assertThat(end.source()).isEqualTo("builtin");
        assertThat(end.toolCallId()).isEqualTo("id");
        assertThat(end.iteration()).isEqualTo(2);
        assertThat(end.resultChars()).isEqualTo(10012);
        assertThat(end.resultTruncated()).isTrue();
        assertThat(end.attempts()).isOne();
        assertThat(worker.get().isAlive()).isFalse();
    }

    @Test
    void unconfirmedBeginAndFatalCheckpointNeverExecute() {
        var executions = new AtomicInteger();
        var recorder = mock(ToolInvocationRecorder.class);
        when(recorder.begin(any()))
                .thenThrow(new AuditWriteException("do not expose KNOWN_SECRET"));
        var tool =
                govern(
                        callback(c -> {
                            executions.incrementAndGet();
                            return "x";
                        }),
                        recorder,
                        Duration.ofSeconds(1));
        assertThatThrownBy(() -> tool.call("{}", context(Duration.ofSeconds(2), () -> {})))
                .isInstanceOf(AuditWriteException.class);
        assertThat(executions).hasValue(0);
        assertThatThrownBy(() ->
                        tool.call(
                                "{}",
                                context(
                                        Duration.ofSeconds(2),
                                        () -> {
                                            throw new LeaseLostException();
                                        })))
                .isInstanceOf(LeaseLostException.class);
        assertThat(executions).hasValue(0);
    }

    @Test
    void invalidJsonBusinessAndSandboxFailuresAreAuditedAndSafe() {
        for (String mode : List.of("json", "business", "sandbox")) {
            var recorder = mock(ToolInvocationRecorder.class);
            when(recorder.begin(any())).thenAnswer(i -> i.getArgument(0));
            var executions = new AtomicInteger();
            var tool =
                    govern(
                            callback(c -> {
                                executions.incrementAndGet();
                                if (mode.equals("sandbox")) {
                                    throw new SandboxViolationException(
                                            "HTTP example.com token=KNOWN_SECRET");
                                }
                                throw new IllegalStateException("password=KNOWN_SECRET");
                            }),
                            recorder,
                            Duration.ofSeconds(1));
            assertThatThrownBy(() ->
                            tool.call(
                                    mode.equals("json") ? "broken" : "{}",
                                    context(Duration.ofSeconds(2), () -> {})))
                    .isInstanceOf(ToolExecutionException.class)
                    .hasMessageNotContaining("KNOWN_SECRET");
            var captor = org.mockito.ArgumentCaptor.forClass(ToolInvocationRecord.class);
            verify(recorder).complete(captor.capture());
            assertThat(captor.getValue().status())
                    .isEqualTo(mode.equals("sandbox") ? "sandbox_denied" : "failed");
            assertThat(captor.getValue().errorMessage()).doesNotContain("KNOWN_SECRET");
            assertThat(executions).hasValue(mode.equals("json") ? 0 : 1);
        }
    }

    @Test
    void ownTimeoutIsRecoverableButWholeTurnTimeoutIsFatal() {
        for (boolean whole : List.of(false, true)) {
            var recorder = mock(ToolInvocationRecorder.class);
            when(recorder.begin(any())).thenAnswer(i -> i.getArgument(0));
            var tool =
                    govern(
                            callback(c -> {
                                try {
                                    Thread.sleep(500);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                                return "late";
                            }),
                            recorder,
                            whole ? Duration.ofSeconds(1) : Duration.ofMillis(30));
            assertThatThrownBy(() ->
                            tool.call(
                                    "{}",
                                    context(
                                            whole ? Duration.ofMillis(70) : Duration.ofSeconds(2),
                                            () -> {})))
                    .isInstanceOf(
                            whole
                                    ? TurnDeadlineExceededException.class
                                    : ToolExecutionException.class);
            var captor = org.mockito.ArgumentCaptor.forClass(ToolInvocationRecord.class);
            verify(recorder).complete(captor.capture());
            assertThat(captor.getValue().status()).isEqualTo("timeout");
        }
    }

    @Test
    void blockedFinalAuditCannotHideWholeTurnTimeoutOrExtendHardStop() throws Exception {
        var recorder = mock(ToolInvocationRecorder.class);
        when(recorder.begin(any())).thenAnswer(i -> i.getArgument(0));
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(
                        ignored -> {
                            release.await();
                            return null;
                        })
                .when(recorder)
                .complete(any());
        var tool =
                govern(
                        callback(ignored -> {
                            try {
                                Thread.sleep(300);
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                            }
                            return "late";
                        }),
                        recorder,
                        Duration.ofSeconds(1));
        long started = System.nanoTime();
        try {
            assertThatThrownBy(() -> tool.call("{}", context(Duration.ofMillis(40), () -> {})))
                    .isInstanceOf(TurnDeadlineExceededException.class);
        } finally {
            release.countDown();
        }
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(1300);
    }

    @Test
    void officialMethodAdapterCannotHideSandboxOrFatalSignals() {
        for (RuntimeException failure :
                List.of(
                        new SandboxViolationException("denied"),
                        new AuditWriteException("audit"),
                        new LeaseLostException(),
                        new TurnDeadlineExceededException())) {
            var recorder = mock(ToolInvocationRecorder.class);
            when(recorder.begin(any())).thenAnswer(i -> i.getArgument(0));
            var tool =
                    govern(
                            callback(c -> {
                                throw new ToolExecutionException(DEF, failure);
                            }),
                            recorder,
                            Duration.ofSeconds(1));
            if (failure instanceof SandboxViolationException) {
                assertThatThrownBy(() -> tool.call("{}", context(Duration.ofSeconds(2), () -> {})))
                        .isInstanceOf(ToolExecutionException.class);
                var record = org.mockito.ArgumentCaptor.forClass(ToolInvocationRecord.class);
                verify(recorder).complete(record.capture());
                assertThat(record.getValue().status()).isEqualTo("sandbox_denied");
            } else {
                assertThatThrownBy(() -> tool.call("{}", context(Duration.ofSeconds(2), () -> {})))
                        .isSameAs(failure);
            }
        }
    }
}
