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
                assertThat(timeout).isPositive().isLessThanOrEqualTo(Duration.ofSeconds(2));
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
    void timeoutDoesNotInterruptDatabaseWorkerOrReplayUnknownWrite() throws Exception {
        var started = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var done = new java.util.concurrent.CountDownLatch(1);
        var interrupted = new java.util.concurrent.atomic.AtomicBoolean();
        var writes = new AtomicInteger();
        var writer =
                new RetryingAuditWriter(
                        new AuditProperties(2, null, null, Duration.ofMillis(100)), e -> true);
        try {
            assertThatThrownBy(() ->
                            writer.write(
                                    TurnExecutionBudget.start(Duration.ofSeconds(5)),
                                    false,
                                    new RetryingAuditWriter.Operation<String>() {
                                        public String write(Duration timeout) {
                                            writes.incrementAndGet();
                                            started.countDown();
                                            try {
                                                release.await();
                                            } catch (InterruptedException e) {
                                                interrupted.set(true);
                                            } finally {
                                                done.countDown();
                                            }
                                            return "late";
                                        }

                                        public boolean confirmed(Duration timeout) {
                                            return false;
                                        }
                                    },
                                    "confirmed"))
                    .isInstanceOf(AuditWriteException.class);
            assertThat(started.getCount()).isZero();
            assertThat(done.getCount()).isOne();
            assertThat(interrupted).isFalse();
            assertThat(writes).hasValue(1);
        } finally {
            release.countDown();
            assertThat(done.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void inFlightFinalAuditCanCrossBusinessDeadlineWithinTheSameHardStop() {
        var budget = TurnExecutionBudget.start(Duration.ofMillis(60));
        var writes = new AtomicInteger();
        var writer = new RetryingAuditWriter(properties, e -> false);
        assertThat(
                        writer.write(
                                budget,
                                true,
                                new RetryingAuditWriter.Operation<String>() {
                                    public String write(Duration timeout) {
                                        writes.incrementAndGet();
                                        try {
                                            java.util.concurrent.TimeUnit.MILLISECONDS.sleep(100);
                                        } catch (InterruptedException e) {
                                            Thread.currentThread().interrupt();
                                            throw new IllegalStateException("测试写入被提前取消");
                                        }
                                        return "committed";
                                    }

                                    public boolean confirmed(Duration timeout) {
                                        return false;
                                    }
                                },
                                "committed"))
                .isEqualTo("committed");
        assertThat(writes).hasValue(1);
        assertThat(budget.auditsConfirmed()).isTrue();
        assertThat(budget.expired()).isTrue();
        assertThat(budget.hardStopped()).isFalse();
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
