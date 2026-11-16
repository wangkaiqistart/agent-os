package com.agentos.tool.govern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentos.core.audit.AuditWriteException;
import com.agentos.core.audit.ToolInvocationRecorder;
import com.agentos.core.tool.ToolPolicy;
import com.agentos.core.turn.ToolContextKeys;
import com.agentos.core.turn.TurnExecutionBudget;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 两种失败必须给出不同事实，且都不能再次执行工具。 */
class AuditFailureTest {
    @Test
    void failedBeginNeverExecutesAndDoesNotExposeCause() {
        var calls = new AtomicInteger();
        var recorder = mock(ToolInvocationRecorder.class);
        when(recorder.begin(any())).thenThrow(new AuditWriteException("password=PRIVATE_SECRET"));
        var context = GovernedToolCallbackTest.context(Duration.ofSeconds(3), () -> {});
        var tool =
                new GovernedToolCallback(
                        GovernedToolCallbackTest.callback(c -> {
                            calls.incrementAndGet();
                            return "ok";
                        }),
                        new ToolPolicy(false, Duration.ofSeconds(1), 100),
                        recorder,
                        GovernedToolCallbackTest.REDACTOR);
        assertThatThrownBy(() -> tool.call("{}", context))
                .isInstanceOf(AuditWriteException.class)
                .hasMessageContaining("执行前")
                .hasMessageNotContaining("PRIVATE_SECRET")
                .hasNoCause();
        assertThat(calls).hasValue(0);
        assertThat(
                        ((TurnExecutionBudget)
                                        context.getContext().get(ToolContextKeys.TURN_BUDGET))
                                .auditsConfirmed())
                .isFalse();
    }

    @Test
    void failedCompleteExecutesExactlyOnceAndWarnsAboutPossibleSideEffect() {
        var calls = new AtomicInteger();
        var recorder = mock(ToolInvocationRecorder.class);
        when(recorder.begin(any())).thenAnswer(c -> c.getArgument(0));
        doThrow(new AuditWriteException("password=PRIVATE_SECRET")).when(recorder).complete(any());
        var context = GovernedToolCallbackTest.context(Duration.ofSeconds(3), () -> {});
        var tool =
                new GovernedToolCallback(
                        GovernedToolCallbackTest.callback(c -> {
                            calls.incrementAndGet();
                            return "ok";
                        }),
                        new ToolPolicy(false, Duration.ofSeconds(1), 100),
                        recorder,
                        GovernedToolCallbackTest.REDACTOR);
        assertThatThrownBy(() -> tool.call("{}", context))
                .isInstanceOf(AuditWriteException.class)
                .hasMessageContaining("可能已经执行")
                .hasMessageNotContaining("PRIVATE_SECRET")
                .hasNoCause();
        assertThat(calls).hasValue(1);
        verify(recorder, times(1)).complete(any());
    }
}
