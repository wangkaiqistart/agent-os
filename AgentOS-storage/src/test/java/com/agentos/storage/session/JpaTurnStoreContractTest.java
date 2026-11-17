package com.agentos.storage.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.core.contract.SessionManagerContractTest;
import com.agentos.core.contract.TurnStoreContractTest;
import com.agentos.core.session.MessageStore;
import com.agentos.core.session.SessionKey;
import com.agentos.core.session.SessionManager;
import com.agentos.core.session.TurnSnapshot;
import com.agentos.core.session.TurnStore;
import com.agentos.core.turn.LeaseLostException;
import com.agentos.core.turn.TurnBudgetRegistry;
import com.agentos.core.turn.TurnDeadlineExceededException;
import com.agentos.core.turn.TurnExecutionBudget;
import com.agentos.storage.support.PostgresIntegrationTest;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** 多连接执行共享契约，不将测试事务传播到竞争线程。 */
@SpringBootTest(
        classes = JpaStorageContractTest.TestApplication.class,
        properties = {
            "spring.main.web-application-type=none",
            "spring.config.import=",
            "agentos.test.phase8=true"
        })
class JpaTurnStoreContractTest extends PostgresIntegrationTest {
    @jakarta.annotation.Resource com.agentos.core.audit.ToolInvocationRecorder toolRecorder;
    @jakarta.annotation.Resource TurnStore turns;
    @jakarta.annotation.Resource SessionManager sessions;
    @jakarta.annotation.Resource MessageStore messages;
    @jakarta.annotation.Resource JdbcTemplate jdbc;

    @jakarta.annotation.Resource SessionRepository repository;
    @jakarta.annotation.Resource AgentTurnRepository turnRepository;
    @jakarta.annotation.Resource SessionMessageRepository messageRepository;
    @jakarta.annotation.Resource com.agentos.storage.audit.AuditTransactions transactions;
    @jakarta.annotation.Resource TurnBudgetRegistry budgets;
    @jakarta.annotation.Resource com.agentos.core.config.TurnProperties properties;

    @Test
    void archiveBetweenConflictInsertAndLockLookupStartsInFreshActiveSession() throws Exception {
        var old = start();
        turns.finishAndRelease(old, "SUCCEEDED", "old", null);
        var arrived = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var first = new java.util.concurrent.atomic.AtomicBoolean(true);
        // 包装端口仅控制竞争时序，实际 SQL/提交由原 Repository 和独立事务执行。
        var controlled =
                org.mockito.Mockito.mock(
                        SessionRepository.class,
                        org.mockito.AdditionalAnswers.delegatesTo(repository));
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            if (first.compareAndSet(true, false)) {
                                arrived.countDown();
                                assertThat(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                                        .isTrue();
                            }
                            return repository.findActiveForUpdate(
                                    old.sessionKey().channel(),
                                    old.sessionKey().userId(),
                                    old.sessionKey().profileName());
                        })
                .when(controlled)
                .findActiveForUpdate(
                        old.sessionKey().channel(),
                        old.sessionKey().userId(),
                        old.sessionKey().profileName());
        var controlledTurns =
                new JpaTurnStore(
                        controlled,
                        turnRepository,
                        messageRepository,
                        jdbc,
                        properties,
                        transactions,
                        budgets);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var pending =
                    executor.submit(() ->
                            controlledTurns.startTurn(
                                    UUID.randomUUID(),
                                    old.sessionKey(),
                                    "test",
                                    "new",
                                    TurnExecutionBudget.start(Duration.ofSeconds(30))));
            try {
                assertThat(arrived.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThat(sessions.archiveIfIdle(old.sessionKey())).isTrue();
            } finally {
                release.countDown();
            }
            var next = pending.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(next.sessionId()).isNotEqualTo(old.sessionId());
            assertThat(sessions.findActive(old.sessionKey()).orElseThrow().sessionId())
                    .isEqualTo(next.sessionId());
            assertThat(turns.find(old.turnId()).orElseThrow().status()).isEqualTo("SUCCEEDED");
        }
    }

    @Test
    void archiveAndStartRacesNeverLeaveRunningTurnInArchivedSession() throws Exception {
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 20; i++) {
                var old = start();
                turns.finishAndRelease(old, "SUCCEEDED", "old", null);
                var gate = new java.util.concurrent.CountDownLatch(1);
                var archive =
                        executor.submit(() -> {
                            gate.await();
                            try {
                                return sessions.archiveIfIdle(old.sessionKey());
                            } catch (com.agentos.core.error.AgentOSException busy) {
                                assertThat(busy.getErrorCode())
                                        .isEqualTo(com.agentos.core.error.ErrorCode.SESSION_BUSY);
                                return false;
                            }
                        });
                var start =
                        executor.submit(() -> {
                            gate.await();
                            return turns.startTurn(
                                    UUID.randomUUID(),
                                    old.sessionKey(),
                                    "test",
                                    "new",
                                    TurnExecutionBudget.start(Duration.ofSeconds(30)));
                        });
                gate.countDown();
                var next = start.get(10, java.util.concurrent.TimeUnit.SECONDS);
                boolean archived = archive.get(10, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(sessions.findActive(old.sessionKey()).orElseThrow().sessionId())
                        .isEqualTo(next.sessionId());
                assertThat(next.sessionId().equals(old.sessionId())).isEqualTo(!archived);
                assertThat(
                                jdbc.queryForObject(
                                        "select count(*) from session_messages where turn_id=?",
                                        Integer.class,
                                        old.turnId()))
                        .isOne();
            }
        }
    }

    @Test
    void messageAppendChecksDatabaseDeadlineAfterWaitingForSessionLock() throws Exception {
        var old = start();
        jdbc.update(
                "update agent_turns set deadline_at=clock_timestamp()+interval '300 milliseconds' where turn_id=?",
                old.turnId());
        try (var locker = jdbc.getDataSource().getConnection();
                var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            locker.setAutoCommit(false);
            try (var statement =
                    locker.prepareStatement(
                            "select session_id from sessions where session_id=? for update")) {
                statement.setObject(1, old.sessionId());
                statement.executeQuery().close();
            }
            var pending =
                    executor.submit(() -> {
                        assertThatThrownBy(() ->
                                        messages.append(
                                                old,
                                                1,
                                                List.of(
                                                        new org.springframework.ai.chat.messages
                                                                .AssistantMessage("late"))))
                                .isInstanceOf(TurnDeadlineExceededException.class);
                    });
            try {
                jdbc.execute("select pg_sleep(0.5)");
                assertThat(pending).isNotDone();
            } finally {
                locker.commit();
            }
            pending.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from session_messages where turn_id=?",
                                    Integer.class,
                                    old.turnId()))
                    .isOne();
        }
    }

    @Test
    void dynamicLeaseCoversWholeTurnAndGraceRatherThanOneProviderAttempt() {
        var budget = TurnExecutionBudget.start(Duration.ofSeconds(300));
        var turn =
                turns.startTurn(
                        UUID.randomUUID(), keyForTest(), "test", "long provider attempts", budget);
        var session = sessions.findActive(turn.sessionKey()).orElseThrow();
        assertThat(session.leaseUntil())
                .isAfterOrEqualTo(turn.deadline().plusSeconds(30).minusMillis(1));
        assertThat(turns.renewLease(turn, budget)).isTrue();
        assertThat(sessions.findActive(turn.sessionKey()).orElseThrow().leaseUntil())
                .isAfterOrEqualTo(turn.deadline().plusSeconds(30).minusMillis(1));
    }

    @Test
    void toolIntentAfterTakeoverIsKnownLeaseLossWithoutNewAuditOrSideEffect() {
        var old = start();
        var budget = TurnExecutionBudget.start(Duration.ofSeconds(30));
        budgets.register(old.turnId(), budget);
        try {
            expireLease(old);
            turns.startTurn(
                    UUID.randomUUID(),
                    old.sessionKey(),
                    "test",
                    "next",
                    TurnExecutionBudget.start(Duration.ofSeconds(30)));
            assertThatThrownBy(() ->
                            toolRecorder.begin(
                                    new com.agentos.core.audit.ToolInvocationRecord(
                                            old.sessionId(),
                                            old.turnId(),
                                            1,
                                            "lost",
                                            "http_get",
                                            "builtin",
                                            "{}",
                                            null,
                                            null,
                                            false,
                                            "in_progress",
                                            null,
                                            true,
                                            0,
                                            null)))
                    .isInstanceOf(LeaseLostException.class);
            assertThat(budget.auditsConfirmed()).isTrue();
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from tool_invocations where turn_id=?",
                                    Integer.class,
                                    old.turnId()))
                    .isZero();
        } finally {
            budgets.remove(old.turnId());
        }
    }

    @Nested
    class Turns extends TurnStoreContractTest {
        protected TurnStore store() {
            return turns;
        }

        protected SessionManager sessions() {
            return sessions;
        }

        protected void expire(TurnSnapshot turn) {
            expireLease(turn);
        }
    }

    @Nested
    class Sessions extends SessionManagerContractTest {
        private final SessionKey key = keyForTest();

        protected SessionManager manager() {
            return sessions;
        }

        protected SessionKey key() {
            return key;
        }
    }

    private static SessionKey keyForTest() {
        return new SessionKey("concurrency", UUID.randomUUID().toString(), "agent");
    }

    private TurnSnapshot start() {
        return turns.startTurn(
                UUID.randomUUID(),
                keyForTest(),
                "test",
                "user",
                TurnExecutionBudget.start(Duration.ofSeconds(30)));
    }

    private void expireLease(TurnSnapshot turn) {
        jdbc.update(
                "update sessions set lease_until=clock_timestamp()-interval '1 second' where session_id=?",
                turn.sessionId());
    }

    @Test
    void oldOwnerCannotAppendAfterTakeoverOrAfterParentHasEnded() {
        var old = start();
        expireLease(old);
        var next =
                turns.startTurn(
                        UUID.randomUUID(),
                        old.sessionKey(),
                        "test",
                        "next",
                        TurnExecutionBudget.start(Duration.ofSeconds(30)));
        assertThatThrownBy(() ->
                        messages.append(
                                old,
                                1,
                                List.of(
                                        new org.springframework.ai.chat.messages.AssistantMessage(
                                                "late"))))
                .isInstanceOf(LeaseLostException.class);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from session_messages where turn_id=?",
                                Integer.class,
                                old.turnId()))
                .isOne();
        turns.finishAndRelease(next, "SUCCEEDED", "ok", null);
        assertThatThrownBy(() ->
                        messages.append(
                                next,
                                1,
                                List.of(
                                        new org.springframework.ai.chat.messages.AssistantMessage(
                                                "late"))))
                .isInstanceOf(LeaseLostException.class);
    }

    @Test
    void cannotRenewCompletedTurnOrDatabaseExpiredDeadlineEvenWithLiveLease() {
        var old = start();
        jdbc.update(
                "update agent_turns set deadline_at=clock_timestamp()-interval '1 second' where turn_id=?",
                old.turnId());
        assertThatThrownBy(() ->
                        turns.renewLease(old, TurnExecutionBudget.start(Duration.ofSeconds(30))))
                .isInstanceOf(TurnDeadlineExceededException.class);
        var other = start();
        jdbc.update("update agent_turns set status='INTERRUPTED' where turn_id=?", other.turnId());
        assertThat(turns.renewLease(other, TurnExecutionBudget.start(Duration.ofSeconds(30))))
                .isFalse();
    }

    @Test
    void expiredOwnerCannotFinishSuccessfullyWithoutAnyTakeover() {
        var old = start();
        expireLease(old);
        var finish = turns.finishAndRelease(old, "SUCCEEDED", "late", null);
        assertThat(finish.status()).isEqualTo("INTERRUPTED");
        assertThat(finish.persistenceConfirmed()).isTrue();
        assertThat(sessions.findActive(old.sessionKey()).orElseThrow().leaseOwner()).isNull();
    }

    @Test
    void archiveRecoversExpiredRunningTurnBeforeArchiving() {
        var old = start();
        expireLease(old);
        assertThat(sessions.archiveIfIdle(old.sessionKey())).isTrue();
        assertThat(turns.find(old.turnId()).orElseThrow().status()).isEqualTo("INTERRUPTED");
    }
}
