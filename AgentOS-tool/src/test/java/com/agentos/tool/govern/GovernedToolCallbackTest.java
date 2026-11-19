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
    void runLogsCarryToolNameStatusAndDurationButNeverArgumentsOrResults() {
        var appender =
                new ch.qos.logback.core.read.ListAppender<
                        ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        var logger =
                (ch.qos.logback.classic.Logger)
                        org.slf4j.LoggerFactory.getLogger(GovernedToolCallback.class);
        logger.addAppender(appender);
        try {
            var recorder = mock(ToolInvocationRecorder.class);
            when(recorder.begin(any())).thenAnswer(i -> i.getArgument(0));
            var ok = govern(callback(c -> "RESULT_SENTINEL"), recorder, Duration.ofSeconds(1));
            ok.call("{}", context(Duration.ofSeconds(5), () -> {}));
            var failing =
                    govern(
                            callback(c -> {
                                throw new IllegalStateException("FAILURE_TEXT_SENTINEL");
                            }),
                            recorder,
                            Duration.ofSeconds(1));
            assertThatThrownBy(() -> failing.call("{}", context(Duration.ofSeconds(5), () -> {})))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            logger.detachAppender(appender);
        }
        var messages =
                appender.list.stream()
                        .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                        .toList();
        assertThat(messages)
                .anyMatch(m -> m.startsWith("工具调用开始 tool=") && m.contains("callId="))
                .anyMatch(m ->
                        m.startsWith("工具调用结束 tool=")
                                && m.contains("status=success")
                                && m.contains("durationMs="))
                .anyMatch(m ->
                        m.startsWith("工具调用结束 tool=")
                                && m.contains("status=failed")
                                && m.contains("failureType=IllegalStateException"));
        // 结果正文和异常文本只能出现在（已脱敏的）审计里，不能进日志。
        assertThat(String.join("\n", messages))
                .doesNotContain("RESULT_SENTINEL", "FAILURE_TEXT_SENTINEL");
    }

    @Test
    void postResultFatalCheckpointStillAuditsOnlyGovernedResultWithoutRetry() {
        for (boolean deadline : List.of(false, true)) {
            var recorder = mock(ToolInvocationRecorder.class);
            when(recorder.begin(any())).thenAnswer(i -> i.getArgument(0));
            var done = new AtomicReference<ToolInvocationRecord>();
            doAnswer(
                            i -> {
                                done.set(i.getArgument(0));
                                return null;
                            })
                    .when(recorder)
                    .complete(any());
            var returned = new java.util.concurrent.atomic.AtomicBoolean();
            var executions = new AtomicInteger();
            String raw = "token=KNOWN_SECRET\n" + "x".repeat(12000);
            var ctx =
                    context(
                            Duration.ofSeconds(5),
                            () -> {
                                if (returned.get()) {
                                    if (deadline) {
                                        throw new TurnDeadlineExceededException();
                                    }
                                    throw new LeaseLostException();
                                }
                            });
            var tool =
                    govern(
                            callback(c -> {
                                executions.incrementAndGet();
                                returned.set(true);
                                return raw;
                            }),
                            recorder,
                            Duration.ofSeconds(1));
            assertThatThrownBy(() -> tool.call("{}", ctx))
                    .isInstanceOf(
                            deadline
                                    ? TurnDeadlineExceededException.class
                                    : LeaseLostException.class);
            assertThat(executions).hasValue(1);
            assertThat(done.get().status()).isEqualTo(deadline ? "timeout" : "interrupted");
            assertThat(done.get().resultText())
                    .hasSizeLessThanOrEqualTo(10000)
                    .contains("结果已截断")
                    .doesNotContain("KNOWN_SECRET");
            assertThat(done.get().resultChars()).isEqualTo(raw.length());
            assertThat(done.get().resultTruncated()).isTrue();
            assertThat(done.get().attempts()).isOne();
        }
    }

    @Test
    void knownLeaseLossAtIntentBoundaryRemainsInterruptedWithoutAuditUncertainty() {
        var recorder = mock(ToolInvocationRecorder.class);
        when(recorder.begin(any())).thenThrow(new LeaseLostException());
        var ctx = context(Duration.ofSeconds(5), () -> {});
        var executions = new AtomicInteger();
        var tool =
                govern(
                        callback(c -> {
                            executions.incrementAndGet();
                            return "unused";
                        }),
                        recorder,
                        Duration.ofSeconds(1));
        assertThatThrownBy(() -> tool.call("{}", ctx)).isInstanceOf(LeaseLostException.class);
        assertThat(executions).hasValue(0);
        assertThat(
                        ((TurnExecutionBudget) ctx.getContext().get(ToolContextKeys.TURN_BUDGET))
                                .auditsConfirmed())
                .isTrue();
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
                        new com.agentos.core.tool.ToolCleanupUnconfirmedException(),
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

    @Test
    void truncationIncludesMarkerAndRedactsBeforeCuttingSecret() {
        for (int cap : List.of(1, 30, 10000)) {
            var recorder = mock(ToolInvocationRecorder.class);
            when(recorder.begin(any())).thenAnswer(i -> i.getArgument(0));
            String raw = "x".repeat(cap - 1) + "KNOWN_SECRET" + "y".repeat(40);
            var tool =
                    new GovernedToolCallback(
                            callback(c -> raw),
                            new ToolPolicy(true, Duration.ofSeconds(1), cap),
                            recorder,
                            REDACTOR);
            String result = tool.call("{}", context(Duration.ofSeconds(3), () -> {}));
            assertThat(result.length()).isLessThanOrEqualTo(cap);
            assertThat(result).doesNotContain("KNOWN");
            if (cap >= 30) {
                assertThat(result).contains("已截断", "原长 " + raw.length());
            }
            var record = org.mockito.ArgumentCaptor.forClass(ToolInvocationRecord.class);
            verify(recorder).complete(record.capture());
            assertThat(record.getValue().resultChars()).isEqualTo(raw.length());
            assertThat(record.getValue().resultTruncated()).isTrue();
        }
    }

    @Test
    void onlyDefinitelyNotStartedIdempotentTransientFailureRetriesAtMostOnce() {
        for (boolean idempotent : List.of(false, true)) {
            for (String mode : List.of("not_started", "started", "unknown", "always")) {
                var recorder = mock(ToolInvocationRecorder.class);
                when(recorder.begin(any())).thenAnswer(i -> i.getArgument(0));
                var executions = new AtomicInteger();
                var tool =
                        new GovernedToolCallback(
                                callback(c -> {
                                    int attempt = executions.incrementAndGet();
                                    if (attempt == 1 || mode.equals("always")) {
                                        if (mode.equals("unknown")) {
                                            throw new IllegalStateException("连接中断");
                                        }
                                        throw new com.agentos.core.tool.TransientToolException(
                                                mode.equals("started"));
                                    }
                                    return "ok";
                                }),
                                new ToolPolicy(idempotent, Duration.ofSeconds(1), 100),
                                recorder,
                                REDACTOR);
                var ctx = context(Duration.ofSeconds(3), () -> {});
                boolean retry = idempotent && (mode.equals("not_started") || mode.equals("always"));
                if (retry && !mode.equals("always")) {
                    assertThat(tool.call("{}", ctx)).isEqualTo("ok");
                } else {
                    assertThatThrownBy(() -> tool.call("{}", ctx))
                            .isInstanceOf(ToolExecutionException.class);
                }
                assertThat(executions).hasValue(retry ? 2 : 1);
                var record = org.mockito.ArgumentCaptor.forClass(ToolInvocationRecord.class);
                verify(recorder).begin(any());
                verify(recorder).complete(record.capture());
                assertThat(record.getValue().attempts()).isEqualTo(executions.get());
            }
        }
    }

    @Test
    void jsonMustBeOneObjectAndInvalidInputNeverStartsTool() {
        for (String invalid : List.of("null", "[]", "1", "{} {}", "{} trailing")) {
            var recorder = mock(ToolInvocationRecorder.class);
            when(recorder.begin(any())).thenAnswer(i -> i.getArgument(0));
            var executions = new AtomicInteger();
            var tool =
                    govern(
                            callback(c -> {
                                executions.incrementAndGet();
                                return "ok";
                            }),
                            recorder,
                            Duration.ofSeconds(1));
            assertThatThrownBy(() -> tool.call(invalid, context(Duration.ofSeconds(3), () -> {})))
                    .isInstanceOf(ToolExecutionException.class);
            assertThat(executions).hasValue(0);
        }
    }

    @Test
    void retryConsumesSameTurnAttemptLimitsAcrossCallsAndIterations() {
        var recorder = mock(ToolInvocationRecorder.class);
        when(recorder.begin(any())).thenAnswer(i -> i.getArgument(0));
        var executions = new AtomicInteger();
        var tool =
                govern(
                        callback(c -> {
                            int n = executions.incrementAndGet();
                            if (n % 2 == 1) {
                                throw new com.agentos.core.tool.TransientToolException(false);
                            }
                            return "ok";
                        }),
                        recorder,
                        Duration.ofSeconds(1));
        var first = context(Duration.ofSeconds(5), () -> {});
        var budget = (TurnExecutionBudget) first.getContext().get(ToolContextKeys.TURN_BUDGET);
        for (int n = 0; n < 5; n++) {
            var values =
                    new java.util.HashMap<String, Object>(
                            context(Duration.ofSeconds(5), () -> {}).getContext());
            values.put(ToolContextKeys.TURN_BUDGET, budget);
            assertThat(tool.call("{}", new ToolContext(values))).isEqualTo("ok");
        }
        assertThat(executions).hasValue(10);
        var values =
                new java.util.HashMap<String, Object>(
                        context(Duration.ofSeconds(5), () -> {}).getContext());
        values.put(ToolContextKeys.TURN_BUDGET, budget);
        assertThatThrownBy(() -> tool.call("{}", new ToolContext(values)))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("上限");
        assertThat(executions).hasValue(10);
        var records = org.mockito.ArgumentCaptor.forClass(ToolInvocationRecord.class);
        verify(recorder, org.mockito.Mockito.times(6)).complete(records.capture());
        assertThat(records.getValue().status()).isEqualTo("limit_skipped");
        assertThat(records.getValue().attempts()).isZero();
    }

    @Test
    void auditInputAndErrorSummariesAreBoundedAfterRedaction() {
        var recorder = mock(ToolInvocationRecorder.class);
        when(recorder.begin(any())).thenAnswer(i -> i.getArgument(0));
        var tool =
                govern(
                        callback(c -> {
                            throw new IllegalStateException(
                                    "token=KNOWN_SECRET " + "e".repeat(20000));
                        }),
                        recorder,
                        Duration.ofSeconds(1));
        assertThatThrownBy(() ->
                        tool.call(
                                "{\"value\":\"" + "i".repeat(20000) + "\"}",
                                context(Duration.ofSeconds(3), () -> {})))
                .isInstanceOf(ToolExecutionException.class)
                .satisfies(e -> assertThat(e.getMessage().length()).isLessThan(1200));
        var records = org.mockito.ArgumentCaptor.forClass(ToolInvocationRecord.class);
        verify(recorder).complete(records.capture());
        assertThat(records.getValue().inputJson().length()).isLessThanOrEqualTo(10000);
        assertThat(records.getValue().errorMessage().length()).isLessThanOrEqualTo(1000);
        assertThat(records.getValue().errorMessage()).doesNotContain("KNOWN_SECRET");
    }

    @Test
    void exhaustedRetryBudgetKeepsAttemptedCallFailedAndDoesNotStartSecondAttempt() {
        var recorder = mock(ToolInvocationRecorder.class);
        when(recorder.begin(any())).thenAnswer(i -> i.getArgument(0));
        var calls = new AtomicInteger();
        var tool =
                new GovernedToolCallback(
                        callback(c -> {
                            calls.incrementAndGet();
                            throw new com.agentos.core.tool.TransientToolException(false);
                        }),
                        new ToolPolicy(true, Duration.ofSeconds(1), 10000),
                        recorder,
                        REDACTOR,
                        1,
                        1);
        assertThatThrownBy(() -> tool.call("{}", context(Duration.ofSeconds(3), () -> {})))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("上限");
        assertThat(calls).hasValue(1);
        var record = org.mockito.ArgumentCaptor.forClass(ToolInvocationRecord.class);
        verify(recorder).complete(record.capture());
        assertThat(record.getValue().status()).isEqualTo("failed");
        assertThat(record.getValue().attempts()).isOne();
    }
}
