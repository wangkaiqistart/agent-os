package com.agentos.core.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class TurnEventPublisherTest {
    private final UUID turnId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();

    private ManagedTurnSink publisher(TurnSink sink, TurnExecutionBudget budget) {
        var publisher = new ManagedTurnSink(sink, budget, turnId);
        publisher.bindSession(sessionId);
        return publisher;
    }

    private static TurnSink recording(List<TurnEvent> events) {
        return new TurnSink() {
            @Override
            public void onEvent(TurnEvent event) {
                events.add(event);
            }
        };
    }

    @Test
    void assignsConsecutiveSequenceFromOneAndBindsIdentity() {
        List<TurnEvent> events = new ArrayList<>();
        var publisher =
                publisher(recording(events), TurnExecutionBudget.start(Duration.ofSeconds(5)));
        publisher.publish(new TurnEventPayload.RunStarted("a", "cli"));
        publisher.publish(new TurnEventPayload.StepStarted(1));
        publisher.finish(new TurnResult(turnId, TurnStatus.SUCCEEDED, "ok", 1, null));
        assertThat(events).extracting(TurnEvent::seq).containsExactly(1L, 2L, 3L);
        assertThat(events)
                .extracting(TurnEvent::type)
                .containsExactly("run.started", "step.started", "run.completed");
        assertThat(events)
                .allSatisfy(event -> {
                    assertThat(event.turnId()).isEqualTo(turnId);
                    assertThat(event.sessionId()).isEqualTo(sessionId);
                    assertThat(event.schemaVersion()).isEqualTo(1);
                });
    }

    @Test
    void publishesFromTwoThreadsInSequenceOrder() throws Exception {
        List<TurnEvent> events = new CopyOnWriteArrayList<>();
        var publisher =
                publisher(recording(events), TurnExecutionBudget.start(Duration.ofSeconds(5)));
        var start = new CountDownLatch(1);
        Runnable task =
                () -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    for (int i = 0; i < 200; i++) {
                        publisher.publish(new TurnEventPayload.StepStarted(i + 1));
                    }
                };
        Thread first = Thread.ofVirtual().start(task);
        Thread second = Thread.ofVirtual().start(task);
        start.countDown();
        first.join();
        second.join();
        assertThat(events).hasSize(400);
        for (int i = 0; i < events.size(); i++) {
            assertThat(events.get(i).seq()).isEqualTo(i + 1L);
        }
    }

    @Test
    void finishOnlyOnceAndRejectsLaterPublish() {
        List<TurnEvent> events = new ArrayList<>();
        var publisher =
                publisher(recording(events), TurnExecutionBudget.start(Duration.ofSeconds(5)));
        publisher.finish(
                new TurnResult(
                        turnId,
                        TurnStatus.FAILED,
                        "失败",
                        1,
                        null,
                        TurnFailureCode.OUTPUT_TRUNCATED));
        publisher.finish(new TurnResult(turnId, TurnStatus.SUCCEEDED, "ok", 1, null));
        assertThatThrownBy(() -> publisher.publish(new TurnEventPayload.StepStarted(2)))
                .isInstanceOf(LeaseLostException.class);
        assertThat(events)
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.payload())
                            .isInstanceOfSatisfying(
                                    TurnEventPayload.RunFailed.class,
                                    failed -> {
                                        assertThat(failed.code())
                                                .isEqualTo(TurnFailureCode.OUTPUT_TRUNCATED);
                                        assertThat(failed.status()).isEqualTo(TurnStatus.FAILED);
                                    });
                });
    }

    @Test
    void publishRejectsTerminalPayload() {
        var publisher = publisher(TurnSink.NOOP, TurnExecutionBudget.start(Duration.ofSeconds(5)));
        assertThatThrownBy(() ->
                        publisher.publish(
                                new TurnEventPayload.RunCompleted(
                                        "x", 1, PersistenceStatus.CONFIRMED)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void failingConsumerInvalidatesAndCancelsWithOutputFailed() {
        var budget = TurnExecutionBudget.start(Duration.ofSeconds(5));
        var publisher =
                publisher(
                        new TurnSink() {
                            @Override
                            public void onEvent(TurnEvent event) {
                                throw new IllegalStateException("broken");
                            }
                        },
                        budget);
        assertThatThrownBy(() -> publisher.publish(new TurnEventPayload.StepStarted(1)))
                .isInstanceOf(LeaseLostException.class);
        assertThat(budget.cancelReason()).isEqualTo(TurnExecutionBudget.CancelReason.OUTPUT_FAILED);
        assertThatThrownBy(() -> publisher.publish(new TurnEventPayload.StepStarted(2)))
                .isInstanceOf(LeaseLostException.class);
    }

    @Test
    void waitingForLockBeyondHardStopInvalidates() throws Exception {
        var budget = TurnExecutionBudget.start(Duration.ofMillis(200), Duration.ofMillis(100));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var publisher =
                publisher(
                        new TurnSink() {
                            @Override
                            public void onEvent(TurnEvent event) {
                                if (event.payload() instanceof TurnEventPayload.StepStarted s
                                        && s.iteration() == 1) {
                                    entered.countDown();
                                    try {
                                        release.await(5, TimeUnit.SECONDS);
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                }
                            }
                        },
                        budget);
        Thread blocker =
                Thread.ofVirtual()
                        .start(() -> publisher.publish(new TurnEventPayload.StepStarted(1)));
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> publisher.publish(new TurnEventPayload.StepStarted(2)))
                .isInstanceOf(LeaseLostException.class);
        assertThat(budget.cancelReason()).isEqualTo(TurnExecutionBudget.CancelReason.OUTPUT_FAILED);
        release.countDown();
        blocker.join();
    }

    @Test
    void publishStillDeliversAfterTurnDeadline() throws Exception {
        List<TurnEvent> events = new ArrayList<>();
        var budget = TurnExecutionBudget.start(Duration.ofMillis(20), Duration.ofSeconds(1));
        var publisher = publisher(recording(events), budget);
        Thread.sleep(50);
        assertThat(budget.expired()).isTrue();
        publisher.publish(
                new TurnEventPayload.ToolFinished(
                        "c1", "t", TurnEventPayload.ToolStatus.TIMEOUT, 10, false, null, false));
        assertThat(events).hasSize(1);
    }

    @Test
    void interruptedPublisherStillDeliversAndKeepsInterruptFlag() {
        List<TurnEvent> events = new ArrayList<>();
        var publisher =
                publisher(recording(events), TurnExecutionBudget.start(Duration.ofSeconds(5)));
        Thread.currentThread().interrupt();
        try {
            publisher.publish(new TurnEventPayload.StepStarted(1));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        assertThat(events).hasSize(1);
    }

    @Test
    void startUnconfirmedEventsMayLackSession() {
        List<TurnEvent> events = new ArrayList<>();
        var publisher =
                new ManagedTurnSink(
                        recording(events),
                        TurnExecutionBudget.start(Duration.ofSeconds(5)),
                        turnId);
        publisher.publish(new TurnEventPayload.RunStarted("a", "cli"));
        publisher.finish(
                new TurnResult(
                        turnId,
                        TurnStatus.FAILED,
                        "未确认",
                        0,
                        PersistenceStatus.UNCONFIRMED,
                        TurnFailureCode.START_UNCONFIRMED));
        assertThat(events).extracting(TurnEvent::sessionId).containsOnlyNulls();
        assertThat(events).extracting(TurnEvent::type).containsExactly("run.started", "run.failed");
    }
}
