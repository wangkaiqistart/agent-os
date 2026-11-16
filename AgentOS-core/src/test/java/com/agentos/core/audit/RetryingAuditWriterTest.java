package com.agentos.core.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.core.config.AuditProperties;
import com.agentos.core.turn.TurnExecutionBudget;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RetryingAuditWriterTest {
    private final AuditProperties properties = new AuditProperties(2, null, null, null);

    private static class TransientFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private RetryingAuditWriter.Operation<String> operation(
            AtomicInteger calls, int failures, boolean confirmed) {
        return new RetryingAuditWriter.Operation<>() {
            public String write(Duration timeout) {
                assertThat(timeout).isPositive().isLessThanOrEqualTo(Duration.ofMillis(250));
                if (calls.incrementAndGet() <= failures) {
                    throw new TransientFailure();
                }
                return "ok";
            }

            public boolean confirmed(Duration timeout) {
                return confirmed;
            }
        };
    }

    @Test
    void retriesOnlyWritesThreeTimesWithBackoffOutsideOperation() {
        var pauses = new ArrayList<Duration>();
        var calls = new AtomicInteger();
        var writer =
                new RetryingAuditWriter(
                        properties, e -> e instanceof TransientFailure, pauses::add);
        assertThat(
                        writer.write(
                                TurnExecutionBudget.start(Duration.ofSeconds(5)),
                                false,
                                operation(calls, 2, false),
                                "ok"))
                .isEqualTo("ok");
        assertThat(calls).hasValue(3);
        assertThat(pauses).containsExactly(Duration.ofMillis(200), Duration.ofMillis(500));
    }

    @Test
    void persistentFailureIsUnconfirmedAndValidationNeverRetries() {
        var calls = new AtomicInteger();
        var pauses = new ArrayList<Duration>();
        var writer =
                new RetryingAuditWriter(
                        properties, e -> e instanceof TransientFailure, pauses::add);
        assertThatThrownBy(() ->
                        writer.write(
                                TurnExecutionBudget.start(Duration.ofSeconds(5)),
                                false,
                                operation(calls, 9, false),
                                "ok"))
                .isInstanceOf(AuditWriteException.class);
        assertThat(calls).hasValue(3);
        calls.set(0);
        pauses.clear();
        var noRetry = new RetryingAuditWriter(properties, e -> false, pauses::add);
        assertThatThrownBy(() ->
                        noRetry.write(
                                TurnExecutionBudget.start(Duration.ofSeconds(5)),
                                false,
                                operation(calls, 9, false),
                                "ok"))
                .isInstanceOf(AuditWriteException.class);
        assertThat(calls).hasValue(1);
        assertThat(pauses).isEmpty();
    }

    @Test
    void committedButResponseLostIsConfirmedWithoutSecondWrite() {
        var calls = new AtomicInteger();
        var writer =
                new RetryingAuditWriter(
                        properties,
                        e -> true,
                        d -> {
                            throw new AssertionError("已提交不能退避重放");
                        });
        assertThat(
                        writer.write(
                                TurnExecutionBudget.start(Duration.ofSeconds(5)),
                                false,
                                operation(calls, 9, true),
                                "ok"))
                .isEqualTo("ok");
        assertThat(calls).hasValue(1);
    }

    @Test
    void finalAuditUsesCleanupOnceAndNoOrdinaryWriteAfterDeadline() {
        var budget = TurnExecutionBudget.start(Duration.ofNanos(1));
        var calls = new AtomicInteger();
        var writer =
                new RetryingAuditWriter(
                        properties,
                        e -> true,
                        d -> {
                            throw new AssertionError("清理不退避");
                        });
        assertThatThrownBy(() -> writer.write(budget, false, operation(calls, 0, false), "ok"))
                .isInstanceOf(AuditWriteException.class);
        assertThat(calls).hasValue(0);
        assertThat(writer.write(budget, true, operation(calls, 0, false), "ok")).isEqualTo("ok");
        assertThat(calls).hasValue(1);
        assertThatThrownBy(() -> writer.write(budget, true, operation(calls, 9, false), "ok"))
                .isInstanceOf(AuditWriteException.class);
        assertThat(calls).hasValue(2);
    }

    @Test
    void missingBudgetIsFailClosedAndNoPauseCanOutliveDeadline() {
        var calls = new AtomicInteger();
        var pauses = new ArrayList<Duration>();
        var writer = new RetryingAuditWriter(properties, e -> true, pauses::add);
        assertThatThrownBy(() -> writer.write(null, true, operation(calls, 0, false), "ok"))
                .isInstanceOf(AuditWriteException.class);
        assertThat(calls).hasValue(0);
        assertThatThrownBy(() ->
                        writer.write(
                                TurnExecutionBudget.start(Duration.ofMillis(100)),
                                false,
                                operation(calls, 9, false),
                                "ok"))
                .isInstanceOf(AuditWriteException.class);
        assertThat(calls).hasValue(1);
        assertThat(pauses).isEmpty();
    }
}
