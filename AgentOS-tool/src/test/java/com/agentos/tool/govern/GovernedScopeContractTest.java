package com.agentos.tool.govern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentos.core.audit.AuditWriteException;
import com.agentos.core.audit.ToolInvocationRecord;
import com.agentos.core.audit.ToolInvocationRecorder;
import com.agentos.core.tool.ToolCleanupUnconfirmedException;
import com.agentos.core.tool.ToolDescriptor;
import com.agentos.core.tool.ToolPolicy;
import com.agentos.core.turn.ToolContextKeys;
import com.agentos.core.turn.ToolExecutor;
import com.agentos.core.turn.TurnContext;
import com.agentos.core.turn.TurnExecutionBudget;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

class GovernedScopeContractTest {
    @Test
    void governorWaitsForWorkerInitiatedCleanupWithinTheExistingWindow() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var cleaned = new java.util.concurrent.atomic.AtomicBoolean();
        var recorder = recorder();
        var delegate =
                GovernedToolCallbackTest.callback(context -> {
                    var scope =
                            (com.agentos.core.tool.ToolExecutionScope)
                                    context.getContext().get(ToolContextKeys.TOOL_SCOPE);
                    scope.register(deadline -> {
                        entered.countDown();
                        boolean done =
                                release.await(
                                        Math.max(0, deadline - System.nanoTime()),
                                        java.util.concurrent.TimeUnit.NANOSECONDS);
                        cleaned.set(done);
                        return done;
                    });
                    scope.close();
                    throw new com.agentos.core.turn.TurnDeadlineExceededException();
                });
        var governed =
                new GovernedToolCallback(
                        delegate,
                        new ToolPolicy(false, Duration.ofMillis(150), 10000),
                        recorder,
                        GovernedToolCallbackTest.REDACTOR);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var result =
                    executor.submit(() ->
                            governed.call(
                                    "{}",
                                    GovernedToolCallbackTest.context(
                                            Duration.ofSeconds(5), () -> {})));
            try {
                assertThat(entered.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                // 工具预算先到期；工作线程自己的清理尚未结束，不能提前发布终结结果。
                assertThatThrownBy(
                                () -> result.get(300, java.util.concurrent.TimeUnit.MILLISECONDS))
                        .isInstanceOf(java.util.concurrent.TimeoutException.class);
                assertThat(cleaned).isFalse();
                release.countDown();
                assertThatThrownBy(() -> result.get(1, java.util.concurrent.TimeUnit.SECONDS))
                        .hasCauseInstanceOf(ToolExecutionException.class);
                assertThat(cleaned).isTrue();
                var end = ArgumentCaptor.forClass(ToolInvocationRecord.class);
                verify(recorder).complete(end.capture());
                assertThat(end.getValue().errorMessage()).doesNotContain("清理未确认");
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void workerClosePreservesFatalReasonWithoutCancellingOrWaitingForItself() {
        for (RuntimeException failure :
                List.of(
                        new AuditWriteException("审计未确认"),
                        new com.agentos.core.turn.LeaseLostException(),
                        new com.agentos.core.turn.TurnDeadlineExceededException())) {
            var recorder = recorder();
            var cleanupCalls = new AtomicInteger();
            var owned =
                    new java.util.concurrent.atomic.AtomicReference<
                            com.agentos.core.tool.ToolExecutionScope>();
            var delegate =
                    GovernedToolCallbackTest.callback(context -> {
                        var scope =
                                (com.agentos.core.tool.ToolExecutionScope)
                                        context.getContext().get(ToolContextKeys.TOOL_SCOPE);
                        owned.set(scope);
                        scope.register(deadline -> {
                            cleanupCalls.incrementAndGet();
                            return true;
                        });
                        scope.close();
                        // 资源虽已关闭，当前委托尚未退出，不能提前确认工作线程停止。
                        assertThat(scope.cleanupConfirmed()).isFalse();
                        throw failure;
                    });
            var governed =
                    new GovernedToolCallback(
                            delegate,
                            new ToolPolicy(false, Duration.ofSeconds(2), 10000),
                            recorder,
                            GovernedToolCallbackTest.REDACTOR);
            assertThatThrownBy(() ->
                            governed.call(
                                    "{}",
                                    GovernedToolCallbackTest.context(
                                            Duration.ofSeconds(5), () -> {})))
                    .isSameAs(failure);
            assertThat(cleanupCalls).hasValue(1);
            assertThat(owned.get().cleanupConfirmed()).isTrue();
            var end = ArgumentCaptor.forClass(ToolInvocationRecord.class);
            verify(recorder).complete(end.capture());
            assertThat(end.getValue().status())
                    .isEqualTo(
                            failure instanceof com.agentos.core.turn.TurnDeadlineExceededException
                                    ? "timeout"
                                    : "interrupted");
            assertThat(end.getValue().attempts()).isOne();
            assertThat(end.getValue().errorMessage()).doesNotContain("清理未确认");
        }
    }

    @Test
    void selfClosedWorkerCannotConfirmCleanupWhileStillRunning() throws Exception {
        var release = new java.util.concurrent.CountDownLatch(1);
        var finished = new java.util.concurrent.CountDownLatch(1);
        var owned =
                new java.util.concurrent.atomic.AtomicReference<
                        com.agentos.core.tool.ToolExecutionScope>();
        var delegate =
                GovernedToolCallbackTest.callback(context -> {
                    var scope =
                            (com.agentos.core.tool.ToolExecutionScope)
                                    context.getContext().get(ToolContextKeys.TOOL_SCOPE);
                    owned.set(scope);
                    try {
                        scope.close();
                        while (release.getCount() > 0) {
                            try {
                                release.await();
                            } catch (InterruptedException ignored) {
                                /* 模拟关闭资源后仍不合作的委托。 */
                            }
                        }
                        return "late";
                    } finally {
                        finished.countDown();
                    }
                });
        var recorder = recorder();
        var governed =
                new GovernedToolCallback(
                        delegate,
                        new ToolPolicy(false, Duration.ofMillis(150), 10000),
                        recorder,
                        GovernedToolCallbackTest.REDACTOR);
        try {
            assertBatchStops(governed, recorder);
            assertThat(finished.getCount()).isOne();
            assertThat(owned.get().cleanupConfirmed()).isFalse();
        } finally {
            release.countDown();
            assertThat(finished.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void cleanupUncertaintyPreservesExistingFatalReasons() {
        for (RuntimeException failure :
                List.of(
                        new AuditWriteException("审计未确认"),
                        new com.agentos.core.turn.LeaseLostException(),
                        new com.agentos.core.turn.TurnDeadlineExceededException())) {
            var recorder = recorder();
            var delegate =
                    GovernedToolCallbackTest.callback(context -> {
                        ((com.agentos.core.tool.ToolExecutionScope)
                                        context.getContext().get(ToolContextKeys.TOOL_SCOPE))
                                .register(deadline -> false);
                        throw failure;
                    });
            var governed =
                    new GovernedToolCallback(
                            delegate,
                            new ToolPolicy(false, Duration.ofSeconds(2), 10000),
                            recorder,
                            GovernedToolCallbackTest.REDACTOR);
            assertThatThrownBy(() ->
                            governed.call(
                                    "{}",
                                    GovernedToolCallbackTest.context(
                                            Duration.ofSeconds(5), () -> {})))
                    .isSameAs(failure);
            var end = ArgumentCaptor.forClass(ToolInvocationRecord.class);
            verify(recorder).complete(end.capture());
            assertThat(end.getValue().errorMessage()).contains("清理未确认");
            assertThat(end.getValue().attempts()).isOne();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unconfirmedCleanupStopsOfficialBatchEvenAfterBusinessFailure(boolean businessFailure) {
        var actions = new AtomicInteger();
        var recorder = recorder();
        var delegate =
                GovernedToolCallbackTest.callback(context -> {
                    actions.incrementAndGet();
                    var scope =
                            (com.agentos.core.tool.ToolExecutionScope)
                                    context.getContext().get(ToolContextKeys.TOOL_SCOPE);
                    scope.register(deadline -> false);
                    if (businessFailure) {
                        throw new IllegalStateException("普通业务失败");
                    }
                    return "已执行";
                });
        var governed =
                new GovernedToolCallback(
                        delegate,
                        new ToolPolicy(false, Duration.ofSeconds(2), 10000),
                        recorder,
                        GovernedToolCallbackTest.REDACTOR,
                        10,
                        30,
                        "java:contract");
        assertBatchStops(governed, recorder);
        assertThat(actions).hasValue(1);
        var end = ArgumentCaptor.forClass(ToolInvocationRecord.class);
        verify(recorder).complete(end.capture());
        assertThat(end.getValue().status()).isEqualTo(businessFailure ? "failed" : "unconfirmed");
        assertThat(end.getValue().toolCallId()).isEqualTo("id");
        assertThat(end.getValue().source()).isEqualTo("java:contract");
        assertThat(end.getValue().attempts()).isOne();
        assertThat(end.getValue().errorMessage()).contains("清理未确认");
    }

    private void assertBatchStops(ToolCallback first, ToolInvocationRecorder recorder) {
        var followups = new AtomicInteger();
        var followup =
                new ToolCallback() {
                    public ToolDefinition getToolDefinition() {
                        return ToolDefinition.builder()
                                .name("followup")
                                .description("后续动作")
                                .inputSchema("{\"type\":\"object\"}")
                                .build();
                    }

                    public String call(String input) {
                        followups.incrementAndGet();
                        return "不应执行";
                    }
                };
        var seed = GovernedToolCallbackTest.context(Duration.ofSeconds(5), () -> {});
        var response =
                new ChatResponse(
                        List.of(
                                new Generation(
                                        AssistantMessage.builder()
                                                .content("")
                                                .toolCalls(
                                                        List.of(
                                                                new AssistantMessage.ToolCall(
                                                                        "id",
                                                                        "function",
                                                                        "http_get",
                                                                        "{}"),
                                                                new AssistantMessage.ToolCall(
                                                                        "next",
                                                                        "function",
                                                                        "followup",
                                                                        "{}")))
                                                .build())));
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ToolCallingAutoConfiguration.class))
                .withBean(
                        ToolExecutionExceptionProcessor.class,
                        () ->
                                new AgentOsToolExecutionExceptionProcessor(
                                        GovernedToolCallbackTest.REDACTOR))
                .run(ctx -> {
                    var executor =
                            new ToolExecutor(
                                    ctx.getBean(ToolCallingManager.class),
                                    recorder,
                                    GovernedToolCallbackTest.REDACTOR);
                    assertThatThrownBy(() ->
                                    executor.execute(
                                            List.of(new UserMessage("测试")),
                                            response,
                                            List.of(
                                                    first,
                                                    new GovernedToolCallback(
                                                            followup,
                                                            new ToolPolicy(
                                                                    false,
                                                                    Duration.ofSeconds(2),
                                                                    10000),
                                                            recorder,
                                                            GovernedToolCallbackTest.REDACTOR)),
                                            (TurnContext)
                                                    seed.getContext()
                                                            .get(ToolContextKeys.TURN_CONTEXT),
                                            (TurnExecutionBudget)
                                                    seed.getContext()
                                                            .get(ToolContextKeys.TURN_BUDGET),
                                            () -> {}))
                            .isInstanceOf(ToolCleanupUnconfirmedException.class)
                            .hasMessageContaining("清理未确认");
                    assertThat(followups).hasValue(0);
                });
        verify(recorder).begin(any());
    }

    @Test
    void toolInitiatedCloseCannotHideInFlightCleanupFromGovernance() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var closed = new java.util.concurrent.CountDownLatch(1);
        var actions = new AtomicInteger();
        var cleanupScope =
                new java.util.concurrent.atomic.AtomicReference<
                        com.agentos.core.tool.ToolExecutionScope>();
        var delegate =
                GovernedToolCallbackTest.callback(context -> {
                    actions.incrementAndGet();
                    var scope =
                            (com.agentos.core.tool.ToolExecutionScope)
                                    context.getContext()
                                            .get(com.agentos.core.turn.ToolContextKeys.TOOL_SCOPE);
                    cleanupScope.set(scope);
                    scope.register(deadline -> {
                        entered.countDown();
                        release.await(
                                Math.max(0, deadline - System.nanoTime()),
                                java.util.concurrent.TimeUnit.NANOSECONDS);
                        return false;
                    });
                    // 工具发起关闭会取消自己的future；治理线程同时接管失败审计。
                    Thread.ofVirtual()
                            .start(() -> {
                                try {
                                    scope.close();
                                } finally {
                                    closed.countDown();
                                }
                            });
                    boolean interrupted = false;
                    try {
                        while (entered.getCount() > 0) {
                            try {
                                entered.await();
                            } catch (InterruptedException ignored) {
                                interrupted = true;
                            }
                        }
                        return "late";
                    } finally {
                        if (interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    }
                });
        var recorder = recorder();
        var governed =
                new GovernedToolCallback(
                        delegate,
                        new ToolPolicy(false, Duration.ofSeconds(2), 10000),
                        recorder,
                        GovernedToolCallbackTest.REDACTOR);
        try {
            assertBatchStops(governed, recorder);
            assertThat(entered.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(release.getCount()).isEqualTo(1);
            assertThat(cleanupScope.get().cleanupConfirmed()).isFalse();
            var end = ArgumentCaptor.forClass(ToolInvocationRecord.class);
            verify(recorder).complete(end.capture());
            assertThat(end.getValue().status()).isNotEqualTo("success");
            assertThat(end.getValue().errorMessage()).contains("清理未确认");
            assertThat(end.getValue().attempts()).isEqualTo(1);
            assertThat(actions).hasValue(1);
        } finally {
            release.countDown();
            assertThat(closed.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
        cleanupScope.get().close();
        assertThat(cleanupScope.get().cleanupConfirmed()).isFalse();
    }

    @Test
    void nonCooperativeDelegateCannotBeReportedAsStoppedOrReplayed() throws Exception {
        var release = new java.util.concurrent.CountDownLatch(1);
        var finished = new java.util.concurrent.CountDownLatch(1);
        var actions = new AtomicInteger();
        var delegate =
                GovernedToolCallbackTest.callback(context -> {
                    actions.incrementAndGet();
                    try {
                        while (release.getCount() > 0) {
                            try {
                                release.await();
                            } catch (InterruptedException ignored) {
                                /* 模拟不合作插件，必须明确报告不确定。 */
                            }
                        }
                        return "late";
                    } finally {
                        finished.countDown();
                    }
                });
        var governed =
                new GovernedToolCallback(
                        delegate,
                        new ToolPolicy(false, Duration.ofMillis(40), 10000),
                        recorder(),
                        GovernedToolCallbackTest.REDACTOR);
        long started = System.nanoTime();
        try {
            assertThatThrownBy(() ->
                            governed.call(
                                    "{}",
                                    GovernedToolCallbackTest.context(
                                            Duration.ofSeconds(3), () -> {})))
                    .isInstanceOf(AuditWriteException.class)
                    .hasMessageContaining("可能已经执行");
            assertThat(actions).hasValue(1);
            assertThat(finished.getCount()).isEqualTo(1);
            assertThat(Duration.ofNanos(System.nanoTime() - started))
                    .isLessThan(Duration.ofMillis(1400));
        } finally {
            release.countDown();
        }
        assertThat(finished.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    }

    private static final String SCHEMA =
            """
            {"type":"object","properties":{"count":{"type":"integer","minimum":1}},
             "required":["count"],"additionalProperties":false}
            """;

    @Test
    void schemaRefusalIsAuditedWithOriginalSourceBeforeAnyAction() {
        for (String input :
                List.of("{}", "{\"count\":0}", "{\"count\":\"1\"}", "{\"count\":1,\"extra\":1}")) {
            var actions = new AtomicInteger();
            var recorder = recorder();
            var governed = tool(recorder, actions, "ok", 10000);
            assertThatThrownBy(() ->
                            governed.call(
                                    input,
                                    GovernedToolCallbackTest.context(
                                            Duration.ofSeconds(3), () -> {})))
                    .isInstanceOf(ToolExecutionException.class);
            assertThat(actions).hasValue(0);
            var end = ArgumentCaptor.forClass(ToolInvocationRecord.class);
            verify(recorder).complete(end.capture());
            assertThat(end.getValue().source()).isEqualTo("java:contract");
            assertThat(end.getValue().toolCallId()).isEqualTo("id");
            assertThat(end.getValue().attempts()).isZero();
        }
    }

    @Test
    void beginFailureIsZeroActionsAndCompletionUncertaintyNeverReplays() {
        var actions = new AtomicInteger();
        var beginFailure = recorder();
        when(beginFailure.begin(any())).thenThrow(new AuditWriteException("未确认"));
        assertThatThrownBy(() ->
                        tool(beginFailure, actions, "ok", 10000)
                                .call(
                                        "{\"count\":1}",
                                        GovernedToolCallbackTest.context(
                                                Duration.ofSeconds(3), () -> {})))
                .isInstanceOf(AuditWriteException.class);
        assertThat(actions).hasValue(0);
        var completeFailure = recorder();
        doThrow(new AuditWriteException("未确认")).when(completeFailure).complete(any());
        assertThatThrownBy(() ->
                        tool(completeFailure, actions, "ok", 10000)
                                .call(
                                        "{\"count\":1}",
                                        GovernedToolCallbackTest.context(
                                                Duration.ofSeconds(3), () -> {})))
                .isInstanceOf(AuditWriteException.class);
        assertThat(actions).hasValue(1);
    }

    @Test
    void structuredResultRemainsValidJsonAndCarriesTruncation() {
        var result =
                tool(
                                recorder(),
                                new AtomicInteger(),
                                "{\"content\":\"" + "x".repeat(1000) + "\",\"truncated\":false}",
                                180)
                        .call(
                                "{\"count\":1}",
                                GovernedToolCallbackTest.context(Duration.ofSeconds(3), () -> {}));
        assertThat(result.length()).isLessThanOrEqualTo(180);
        var node = JsonMapper.builder().build().readTree(result);
        assertThat(node.isObject()).isTrue();
        assertThat(node.path("truncated").asBoolean()).isTrue();
    }

    private ToolInvocationRecorder recorder() {
        var recorder = mock(ToolInvocationRecorder.class);
        when(recorder.begin(any())).thenAnswer(call -> call.getArgument(0));
        return recorder;
    }

    private GovernedToolCallback tool(
            ToolInvocationRecorder recorder, AtomicInteger actions, String result, int cap) {
        var callback =
                new ToolCallback() {
                    public ToolDefinition getToolDefinition() {
                        return ToolDefinition.builder()
                                .name("http_get")
                                .description("测试插件")
                                .inputSchema(SCHEMA)
                                .build();
                    }

                    public String call(String input) {
                        actions.incrementAndGet();
                        return result;
                    }

                    public String call(String input, ToolContext context) {
                        return call(input);
                    }
                };
        var descriptor =
                new ToolDescriptor(
                        "http_get",
                        "测试插件",
                        SCHEMA,
                        "java:contract",
                        null,
                        new ToolPolicy(false, Duration.ofSeconds(2), cap),
                        callback.getToolMetadata(),
                        callback,
                        callback,
                        ToolDescriptor.Availability.AVAILABLE,
                        1);
        return new GovernedToolCallback(
                descriptor, recorder, GovernedToolCallbackTest.REDACTOR, 10, 30);
    }
}
