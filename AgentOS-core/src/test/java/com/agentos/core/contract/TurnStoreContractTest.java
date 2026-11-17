package com.agentos.core.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.core.session.SessionKey;
import com.agentos.core.session.TurnSnapshot;
import com.agentos.core.session.TurnStore;
import com.agentos.core.turn.TurnExecutionBudget;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** TurnStore 的共享最小契约。并发与接管场景由 US6/US5 的专门测试覆盖。 */
public abstract class TurnStoreContractTest {
    protected abstract TurnStore store();

    protected abstract com.agentos.core.session.SessionManager sessions();

    protected abstract void expire(TurnSnapshot turn);

    protected SessionKey uniqueKey() {
        return new SessionKey("contract", UUID.randomUUID().toString(), "agent");
    }

    protected TurnSnapshot start(SessionKey key) {
        return store().startTurn(
                        UUID.randomUUID(),
                        key,
                        "test",
                        "hello",
                        TurnExecutionBudget.start(Duration.ofSeconds(30)));
    }

    @Test
    void competingStartsHaveOneWinnerAndBusyRequestsLeaveOwnerUnchanged() throws Exception {
        SessionKey key = uniqueKey();
        var gate = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<TurnSnapshot>>();
            for (int i = 0; i < 8; i++) {
                futures.add(
                        executor.submit(() -> {
                            gate.await();
                            try {
                                return start(key);
                            } catch (com.agentos.core.error.AgentOSException busy) {
                                assertThat(busy.getErrorCode())
                                        .isEqualTo(com.agentos.core.error.ErrorCode.SESSION_BUSY);
                                return null;
                            }
                        }));
            }
            gate.countDown();
            var winners = new java.util.ArrayList<TurnSnapshot>();
            for (var future : futures) {
                var turn = future.get(10, java.util.concurrent.TimeUnit.SECONDS);
                if (turn != null) {
                    winners.add(turn);
                }
            }
            assertThat(winners).hasSize(1);
            assertThat(sessions().findActive(key).orElseThrow().leaseOwner())
                    .isEqualTo(winners.getFirst().owner());
        }
    }

    @Test
    void expiredOwnerCannotRenewOrReleaseTheReplacementAndLateFinishUsesDatabaseStatus() {
        var first = start(uniqueKey());
        expire(first);
        assertThat(store().renewLease(first, TurnExecutionBudget.start(Duration.ofSeconds(30))))
                .isFalse();
        var second = start(first.sessionKey());
        assertThat(second.sessionId()).isEqualTo(first.sessionId());
        assertThat(store().renewLease(first, TurnExecutionBudget.start(Duration.ofSeconds(30))))
                .isFalse();
        var finish = store().finishAndRelease(first, "SUCCEEDED", "late", null);
        assertThat(finish.persistenceConfirmed()).isTrue();
        assertThat(finish.status()).isEqualTo("INTERRUPTED");
        assertThat(sessions().findActive(first.sessionKey()).orElseThrow().leaseOwner())
                .isEqualTo(second.owner());
        store().finishAndRelease(second, "FAILED", "stored", "failed");
        var repeated = store().finishAndRelease(second, "SUCCEEDED", "late", null);
        assertThat(repeated.persistenceConfirmed()).isTrue();
        assertThat(repeated.status()).isEqualTo("FAILED");
        assertThat(repeated.text()).isEqualTo("stored");
    }

    @Test
    void busyArchiveIsRejectedAndCompletedHistoryCanBeArchived() {
        var first = start(uniqueKey());
        assertThatThrownBy(() -> sessions().archiveIfIdle(first.sessionKey()))
                .isInstanceOfSatisfying(
                        com.agentos.core.error.AgentOSException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(com.agentos.core.error.ErrorCode.SESSION_BUSY));
        store().finishAndRelease(first, "SUCCEEDED", "ok", null);
        assertThat(sessions().archiveIfIdle(first.sessionKey())).isTrue();
        assertThat(sessions().findActive(first.sessionKey())).isEmpty();
        var second = start(first.sessionKey());
        assertThat(second.sessionId()).isNotEqualTo(first.sessionId());
    }

    @Test
    void startsAndFinishesOneTurn() {
        UUID id = UUID.randomUUID();
        TurnSnapshot turn =
                store().startTurn(
                                id,
                                new SessionKey("contract", "user", "agent"),
                                "test",
                                "hello",
                                TurnExecutionBudget.start(Duration.ofSeconds(2)));
        assertThat(store().find(id))
                .hasValueSatisfying(snapshot -> assertThat(snapshot.status()).isEqualTo("RUNNING"));
        assertThat(store().finishAndRelease(turn, "SUCCEEDED", "ok", null).persistenceConfirmed())
                .isTrue();
        assertThat(store().find(id))
                .hasValueSatisfying(t -> assertThat(t.status()).isEqualTo("SUCCEEDED"));
    }
}
