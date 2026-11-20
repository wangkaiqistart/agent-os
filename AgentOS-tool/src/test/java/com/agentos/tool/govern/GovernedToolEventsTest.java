package com.agentos.tool.govern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentos.core.audit.AuditWriteException;
import com.agentos.core.audit.ToolInvocationRecord;
import com.agentos.core.audit.ToolInvocationRecorder;
import com.agentos.core.tool.SandboxViolationException;
import com.agentos.core.tool.ToolPolicy;
import com.agentos.core.turn.ToolContextKeys;
import com.agentos.core.turn.TurnEventPayload;
import com.agentos.core.turn.TurnEventPublisher;
import com.agentos.core.turn.TurnExecutionBudget;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;

/** 治理层按单个工具发开始 / 结束事件，状态与审计一一对应，内容已脱敏。 */
class GovernedToolEventsTest {
    private final List<TurnEventPayload> events = new ArrayList<>();

    private ToolContext context(Duration turn) {
        var values =
                new HashMap<String, Object>(
                        GovernedToolCallbackTest.context(turn, () -> {}).getContext());
        TurnEventPublisher publisher = events::add;
        values.put(ToolContextKeys.TURN_EVENTS, publisher);
        return new ToolContext(values);
    }

    private static ToolInvocationRecorder recorder() {
        var recorder = mock(ToolInvocationRecorder.class);
        when(recorder.begin(any())).thenAnswer(i -> i.getArgument(0));
        return recorder;
    }

    private static GovernedToolCallback govern(
            Function<ToolContext, String> fn, ToolInvocationRecorder recorder, Duration timeout) {
        return new GovernedToolCallback(
                GovernedToolCallbackTest.callback(fn),
                new ToolPolicy(false, timeout, 10000),
                recorder,
                GovernedToolCallbackTest.REDACTOR);
    }

    private static ToolInvocationRecord completed(ToolInvocationRecorder recorder) {
        var captor = ArgumentCaptor.forClass(ToolInvocationRecord.class);
        verify(recorder).complete(captor.capture());
        return captor.getValue();
    }

    private void assertPair(ToolInvocationRecord audit, TurnEventPayload.ToolStatus status) {
        assertThat(events).hasSize(2);
        var started = (TurnEventPayload.ToolStarted) events.get(0);
        var finished = (TurnEventPayload.ToolFinished) events.get(1);
        assertThat(started.toolCallId()).isEqualTo("id").isEqualTo(finished.toolCallId());
        assertThat(started.toolName()).isEqualTo("http_get");
        assertThat(started.iteration()).isEqualTo(2);
        assertThat(started.argumentsSummary()).isEqualTo(audit.inputJson());
        assertThat(finished.status()).isEqualTo(status);
        assertThat(TurnEventPayload.ToolStatus.fromAudit(audit.status())).isEqualTo(status);
        assertThat(finished.durationMs()).isEqualTo(audit.durationMs());
        assertThat(finished.truncated()).isEqualTo(audit.resultTruncated());
    }

    @Test
    void successCarriesSameRedactedArgumentsAndResultAsAudit() {
        var recorder = recorder();
        var tool =
                govern(
                        c -> "result KNOWN_SECRET sk-abcdefghijklmnop",
                        recorder,
                        Duration.ofSeconds(1));
        tool.call("{\"token\":\"KNOWN_SECRET\"}", context(Duration.ofSeconds(5)));

        var audit = completed(recorder);
        assertPair(audit, TurnEventPayload.ToolStatus.SUCCESS);
        var finished = (TurnEventPayload.ToolFinished) events.get(1);
        assertThat(finished.resultSummary()).isEqualTo(audit.resultText());
        assertThat(finished.possiblyExecuted()).isTrue();
        assertThat(events.toString()).doesNotContain("KNOWN_SECRET");
    }

    @Test
    void businessFailureIsFailedWithErrorSummary() {
        var recorder = recorder();
        var tool =
                govern(
                        c -> {
                            throw new IllegalStateException("boom KNOWN_SECRET");
                        },
                        recorder,
                        Duration.ofSeconds(1));
        assertThatThrownBy(() -> tool.call("{}", context(Duration.ofSeconds(5))))
                .isInstanceOf(RuntimeException.class);

        var audit = completed(recorder);
        assertPair(audit, TurnEventPayload.ToolStatus.FAILED);
        assertThat(((TurnEventPayload.ToolFinished) events.get(1)).resultSummary())
                .isEqualTo(audit.errorMessage())
                .doesNotContain("KNOWN_SECRET");
    }

    @Test
    void ownTimeoutIsTimeout() {
        var recorder = recorder();
        var tool =
                govern(
                        c -> {
                            try {
                                Thread.sleep(500);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return "late";
                        },
                        recorder,
                        Duration.ofMillis(30));
        assertThatThrownBy(() -> tool.call("{}", context(Duration.ofSeconds(2))))
                .isInstanceOf(RuntimeException.class);
        assertPair(completed(recorder), TurnEventPayload.ToolStatus.TIMEOUT);
    }

    @Test
    void sandboxDenialIsDenied() {
        var recorder = recorder();
        var tool =
                govern(
                        c -> {
                            throw new SandboxViolationException("DENIED：用户拒绝了该操作");
                        },
                        recorder,
                        Duration.ofSeconds(1));
        assertThatThrownBy(() -> tool.call("{}", context(Duration.ofSeconds(5))))
                .isInstanceOf(RuntimeException.class);
        assertPair(completed(recorder), TurnEventPayload.ToolStatus.DENIED);
        assertThat(((TurnEventPayload.ToolFinished) events.get(1)).possiblyExecuted()).isFalse();
    }

    @Test
    void exhaustedAttemptsAreSkipped() {
        var recorder = recorder();
        var tool =
                new GovernedToolCallback(
                        GovernedToolCallbackTest.callback(c -> "ok"),
                        new ToolPolicy(false, Duration.ofSeconds(1), 10000),
                        recorder,
                        GovernedToolCallbackTest.REDACTOR,
                        0,
                        30);
        assertThatThrownBy(() -> tool.call("{}", context(Duration.ofSeconds(5))))
                .isInstanceOf(RuntimeException.class);
        assertPair(completed(recorder), TurnEventPayload.ToolStatus.SKIPPED);
    }

    @Test
    void failedBeginPublishesNothing() {
        var recorder = mock(ToolInvocationRecorder.class);
        when(recorder.begin(any())).thenThrow(new IllegalStateException("db down"));
        var tool = govern(c -> "ok", recorder, Duration.ofSeconds(1));
        assertThatThrownBy(() -> tool.call("{}", context(Duration.ofSeconds(5))))
                .isInstanceOf(AuditWriteException.class);
        assertThat(events).isEmpty();
    }

    @Test
    void userCancelledTurnMarksToolInterrupted() {
        var recorder = recorder();
        var ctx = context(Duration.ofSeconds(5));
        var budget = (TurnExecutionBudget) ctx.getContext().get(ToolContextKeys.TURN_BUDGET);
        var tool =
                govern(
                        c -> {
                            budget.cancel(TurnExecutionBudget.CancelReason.USER);
                            try {
                                Thread.sleep(2000);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return "late";
                        },
                        recorder,
                        Duration.ofSeconds(3));
        assertThatThrownBy(() -> tool.call("{}", ctx)).isInstanceOf(RuntimeException.class);
        var audit = completed(recorder);
        assertThat(audit.status()).isEqualTo("interrupted");
        assertPair(audit, TurnEventPayload.ToolStatus.INTERRUPTED);
    }
}
