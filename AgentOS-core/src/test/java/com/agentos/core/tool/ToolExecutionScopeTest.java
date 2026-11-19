package com.agentos.core.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.core.turn.LeaseLostException;
import com.agentos.core.turn.TurnDeadlineExceededException;
import com.agentos.core.turn.TurnExecutionBudget;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ToolExecutionScopeTest {
    @Test
    void concurrentIoCheckpointsDoNotCompeteForTheSameLease() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var secondStarted = new java.util.concurrent.CountDownLatch(1);
        var renewals = new AtomicInteger();
        var active = new AtomicInteger();
        var scope =
                new ToolExecutionScope(
                        null,
                        "io-checkpoints",
                        TurnExecutionBudget.start(Duration.ofSeconds(3)),
                        Duration.ofSeconds(2),
                        () -> {
                            if (active.incrementAndGet() > 1) {
                                active.decrementAndGet();
                                throw new LeaseLostException();
                            }
                            try {
                                if (renewals.incrementAndGet() == 1) {
                                    entered.countDown();
                                    release.await(1, java.util.concurrent.TimeUnit.SECONDS);
                                }
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new LeaseLostException();
                            } finally {
                                active.decrementAndGet();
                            }
                        });
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(scope::checkpoint);
            try {
                assertThat(entered.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                var second =
                        executor.submit(() -> {
                            secondStarted.countDown();
                            scope.checkpoint();
                        });
                assertThat(secondStarted.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(
                                () -> second.get(100, java.util.concurrent.TimeUnit.MILLISECONDS))
                        .isInstanceOf(java.util.concurrent.TimeoutException.class);
                release.countDown();
                first.get(1, java.util.concurrent.TimeUnit.SECONDS);
                second.get(1, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(renewals).hasValue(2);
            } finally {
                release.countDown();
            }
        } finally {
            scope.close();
        }
    }

    @Test
    void queuedCheckpointExpiresWithoutStartingAnotherLeaseRenewal() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var renewals = new AtomicInteger();
        var scope =
                new ToolExecutionScope(
                        null,
                        "queued-checkpoint",
                        TurnExecutionBudget.start(Duration.ofSeconds(2)),
                        Duration.ofMillis(250),
                        () -> {
                            if (renewals.incrementAndGet() == 1) {
                                entered.countDown();
                                try {
                                    release.await(1, java.util.concurrent.TimeUnit.SECONDS);
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                        });
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(scope::checkpoint);
            try {
                assertThat(entered.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                var second = executor.submit(scope::checkpoint);
                assertThatThrownBy(() -> second.get(1, java.util.concurrent.TimeUnit.SECONDS))
                        .hasCauseInstanceOf(ToolExecutionScope.ToolTimeoutException.class);
                assertThat(renewals).hasValue(1);
            } finally {
                release.countDown();
                try {
                    first.get(1, java.util.concurrent.TimeUnit.SECONDS);
                } catch (java.util.concurrent.ExecutionException expected) {
                    assertThat(expected)
                            .hasCauseInstanceOf(ToolExecutionScope.ToolTimeoutException.class);
                }
            }
        } finally {
            scope.close();
        }
    }

    @Test
    void cancellationStopsQueuedCheckpointBeforeTheOriginalDeadline() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var waiting = new java.util.concurrent.CountDownLatch(1);
        var renewals = new AtomicInteger();
        var budget = TurnExecutionBudget.start(Duration.ofSeconds(3));
        var scope =
                new ToolExecutionScope(
                        null,
                        "cancel-queued-checkpoint",
                        budget,
                        Duration.ofSeconds(2),
                        () -> {
                            renewals.incrementAndGet();
                            entered.countDown();
                            try {
                                release.await(2, java.util.concurrent.TimeUnit.SECONDS);
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                            }
                        });
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(scope::checkpoint);
            try {
                assertThat(entered.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                var second =
                        executor.submit(() -> {
                            waiting.countDown();
                            scope.checkpoint();
                        });
                assertThat(waiting.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(
                                () -> second.get(100, java.util.concurrent.TimeUnit.MILLISECONDS))
                        .isInstanceOf(java.util.concurrent.TimeoutException.class);
                budget.cancel();
                assertThatThrownBy(
                                () -> second.get(500, java.util.concurrent.TimeUnit.MILLISECONDS))
                        .hasCauseInstanceOf(TurnDeadlineExceededException.class);
                assertThat(renewals).hasValue(1);
            } finally {
                release.countDown();
                assertThatThrownBy(() -> first.get(1, java.util.concurrent.TimeUnit.SECONDS))
                        .hasCauseInstanceOf(TurnDeadlineExceededException.class);
            }
        } finally {
            scope.close();
        }
    }

    @Test
    void concurrentCloseNeverConfirmsInFlightCleanupAndSharesTerminalResult() throws Exception {
        for (boolean outcome : java.util.List.of(false, true)) {
            var entered = new java.util.concurrent.CountDownLatch(1);
            var release = new java.util.concurrent.CountDownLatch(1);
            var deadlines = java.util.Collections.synchronizedList(new ArrayList<Long>());
            var scope =
                    new ToolExecutionScope(
                            null,
                            "race",
                            TurnExecutionBudget.start(Duration.ofSeconds(5)),
                            Duration.ofSeconds(2),
                            () -> {});
            scope.register(deadline -> {
                deadlines.add(deadline);
                entered.countDown();
                return release.await(
                                Math.max(0, deadline - System.nanoTime()),
                                java.util.concurrent.TimeUnit.NANOSECONDS)
                        && outcome;
            });
            scope.register(deadline -> {
                deadlines.add(deadline);
                return true;
            });
            var closer =
                    new java.util.concurrent.FutureTask<Void>(() -> {
                        scope.close();
                        return null;
                    });
            Thread.ofVirtual().start(closer);
            try {
                assertThat(entered.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThat(scope.cleanupConfirmed()).isFalse();
                long remaining = scope.remainingCleanupNanos();
                // 并发调用可保守立即返回，但清理仍在途时不能确认成功或重置窗口。
                scope.close();
                assertThat(scope.cleanupConfirmed()).isFalse();
                assertThat(scope.safeReason()).isEqualTo("CLEANUP_UNCONFIRMED");
                assertThat(scope.remainingCleanupNanos()).isLessThanOrEqualTo(remaining);
            } finally {
                release.countDown();
                closer.get(2, java.util.concurrent.TimeUnit.SECONDS);
            }
            assertThat(scope.cleanupConfirmed()).isEqualTo(outcome);
            scope.close();
            assertThat(scope.cleanupConfirmed()).isEqualTo(outcome);
            assertThat(deadlines).hasSize(2);
            assertThat(deadlines.get(0)).isEqualTo(deadlines.get(1));
        }
    }

    @Test
    void recursiveCloseAndCancelledInterruptedCallerDoNotWaitForThemselves() throws Exception {
        var budget = TurnExecutionBudget.start(Duration.ofSeconds(5));
        var scope = new ToolExecutionScope(null, "cancel", budget, Duration.ofSeconds(2), () -> {});
        var calls = new AtomicInteger();
        scope.register(deadline -> {
            calls.incrementAndGet();
            scope.close();
            assertThat(scope.cleanupConfirmed()).isFalse();
            assertThat(deadline - System.nanoTime())
                    .isLessThanOrEqualTo(Duration.ofSeconds(1).toNanos());
            return true;
        });
        budget.cancel();
        Thread.currentThread().interrupt();
        try {
            scope.close();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(scope.cleanupConfirmed()).isTrue();
            scope.close();
            assertThat(calls).hasValue(1);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void interruptedCleanupWaitIsStickyUnconfirmedAndDoesNotExtendDeadline() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var scope =
                new ToolExecutionScope(
                        null,
                        "interrupt",
                        TurnExecutionBudget.start(Duration.ofSeconds(5)),
                        Duration.ofSeconds(2),
                        () -> {});
        scope.register(deadline -> {
            entered.countDown();
            return release.await(1, java.util.concurrent.TimeUnit.SECONDS);
        });
        var restored = new java.util.concurrent.atomic.AtomicBoolean();
        var done = new java.util.concurrent.CountDownLatch(1);
        var closer =
                Thread.ofVirtual()
                        .start(() -> {
                            scope.close();
                            restored.set(Thread.currentThread().isInterrupted());
                            done.countDown();
                        });
        try {
            assertThat(entered.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            closer.interrupt();
            assertThat(done.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(restored).isTrue();
            assertThat(scope.cleanupConfirmed()).isFalse();
            long remaining = scope.remainingCleanupNanos();
            scope.close();
            assertThat(scope.remainingCleanupNanos()).isLessThanOrEqualTo(remaining);
            assertThat(scope.cleanupConfirmed()).isFalse();
        } finally {
            release.countDown();
        }
    }

    @Test
    void lesserDeadlineIsFixedAndChecksCancellationAndLease() {
        var clock = new AtomicLong(100);
        var budget = TurnExecutionBudget.start(Duration.ofSeconds(20));
        var checkpoints = new AtomicInteger();
        var scope =
                new ToolExecutionScope(
                        null,
                        "original",
                        budget,
                        Duration.ofNanos(50),
                        checkpoints::incrementAndGet,
                        clock::get);
        assertThat(scope.budget()).isSameAs(budget);
        assertThat(scope.callId()).isEqualTo("original");
        assertThat(scope.deadlineNanos()).isEqualTo(150);
        clock.set(120);
        scope.checkpoint();
        assertThat(scope.remainingNanos()).isEqualTo(30);
        assertThat(checkpoints).hasValue(1);
        clock.set(150);
        assertThatThrownBy(scope::checkpoint)
                .isInstanceOf(ToolExecutionScope.ToolTimeoutException.class);
        var turnShort = new ToolExecutionScope(null, "id", budget, Duration.ofHours(1), () -> {});
        assertThat(turnShort.remainingNanos())
                .isLessThanOrEqualTo(budget.remainingNanos() + 1_000_000);
        budget.cancel();
        assertThatThrownBy(turnShort::checkpoint).isInstanceOf(TurnDeadlineExceededException.class);
        var lease =
                new ToolExecutionScope(
                        null,
                        "id",
                        TurnExecutionBudget.start(Duration.ofSeconds(5)),
                        Duration.ofSeconds(2),
                        () -> {
                            throw new LeaseLostException();
                        });
        assertThatThrownBy(lease::checkpoint).isInstanceOf(LeaseLostException.class);
    }

    @Test
    void resourcesShareOneCleanupDeadlineAndUnregisterDoesNotCancel() {
        var clock = new AtomicLong(100);
        var scope =
                new ToolExecutionScope(
                        null,
                        "id",
                        TurnExecutionBudget.start(Duration.ofSeconds(5)),
                        Duration.ofSeconds(2),
                        () -> {},
                        clock::get);
        var deadlines = java.util.Collections.synchronizedList(new ArrayList<Long>());
        var unregistered = new AtomicInteger();
        scope.register(d -> {
            deadlines.add(d);
            clock.addAndGet(10);
            return true;
        });
        scope.register(d -> {
            deadlines.add(d);
            return false;
        });
        scope.register(
                        d -> {
                            unregistered.incrementAndGet();
                            return true;
                        })
                .close();
        scope.possiblyStarted();
        assertThat(scope.executionState())
                .isEqualTo(ToolExecutionScope.ExecutionState.POSSIBLY_STARTED);
        scope.close();
        scope.close();
        assertThat(deadlines).hasSize(2);
        assertThat(deadlines.get(0)).isEqualTo(deadlines.get(1));
        assertThat(unregistered).hasValue(0);
        assertThat(scope.cleanupConfirmed()).isFalse();
        assertThat(scope.safeReason()).isEqualTo("CLEANUP_UNCONFIRMED");
    }

    @Test
    void inToolCheckpointsReuseRecentRenewalButStillObserveCancellation() {
        var renewals = new AtomicInteger();
        var clock = new AtomicLong(System.nanoTime());
        var budget = TurnExecutionBudget.start(Duration.ofSeconds(30));
        var scope =
                new ToolExecutionScope(
                        null,
                        "throttled",
                        budget,
                        Duration.ofSeconds(10),
                        renewals::incrementAndGet,
                        Duration.ofSeconds(1),
                        clock::get);
        try {
            // 治理边界总是续租，并作为后续复用的起点。
            scope.checkpointAndRenew();
            for (int i = 0; i < 1000; i++) {
                scope.checkpoint();
            }
            assertThat(renewals).hasValue(1);
            clock.addAndGet(Duration.ofMillis(1001).toNanos());
            scope.checkpoint();
            assertThat(renewals).hasValue(2);
            scope.checkpointAndRenew();
            assertThat(renewals).hasValue(3);
            // 复用续租期间取消仍立即生效，不等待下一次续租。
            budget.cancel();
            assertThatThrownBy(scope::checkpoint).isInstanceOf(TurnDeadlineExceededException.class);
            assertThat(renewals).hasValue(3);
        } finally {
            scope.close();
        }
    }

    @Test
    void interruptedThreadDoesNotReuseRecentRenewal() {
        var renewals = new AtomicInteger();
        var scope =
                new ToolExecutionScope(
                        null,
                        "interrupted",
                        TurnExecutionBudget.start(Duration.ofSeconds(30)),
                        Duration.ofSeconds(10),
                        () -> {
                            renewals.incrementAndGet();
                            if (Thread.currentThread().isInterrupted()) {
                                throw new LeaseLostException();
                            }
                        },
                        Duration.ofSeconds(60));
        try {
            scope.checkpointAndRenew();
            Thread.currentThread().interrupt();
            assertThatThrownBy(scope::checkpoint).isInstanceOf(LeaseLostException.class);
        } finally {
            Thread.interrupted();
            scope.close();
        }
    }
}
