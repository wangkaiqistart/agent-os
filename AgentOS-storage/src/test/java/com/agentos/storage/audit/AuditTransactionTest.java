package com.agentos.storage.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.agentos.core.config.TurnProperties;
import com.agentos.core.provider.CallMode;
import com.agentos.core.provider.LlmCallRecord;
import com.agentos.core.provider.LlmCallRecorder;
import com.agentos.core.session.SessionKey;
import com.agentos.core.session.TurnStore;
import com.agentos.core.turn.TurnBudgetRegistry;
import com.agentos.core.turn.TurnExecutionBudget;
import com.agentos.storage.session.LlmCallEntity;
import com.agentos.storage.session.LlmCallRepository;
import com.agentos.storage.support.PostgresIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** 真实 PostgreSQL 提交和连接，故障只注入审计仓库，不连接共享环境。 */
@SpringBootTest(
        classes = AuditTransactionTest.App.class,
        properties = {
            "spring.main.web-application-type=none",
            "spring.config.import=",
            "agentos.audit.max-retries=2",
            "spring.datasource.hikari.maximum-pool-size=2",
            "spring.datasource.hikari.connection-timeout=250"
        })
class AuditTransactionTest extends PostgresIntegrationTest {
    @jakarta.annotation.Resource LlmCallRecorder recorder;
    @jakarta.annotation.Resource TurnStore turns;
    @jakarta.annotation.Resource TurnBudgetRegistry budgets;
    @jakarta.annotation.Resource JdbcTemplate jdbc;
    @jakarta.persistence.PersistenceContext jakarta.persistence.EntityManager entityManager;
    @MockitoSpyBean LlmCallRepository repository;

    @AfterEach
    void resetSpy() {
        reset(repository);
    }

    private LlmCallRecord record() {
        UUID id = UUID.randomUUID();
        var budget = TurnExecutionBudget.start(Duration.ofSeconds(8));
        var turn =
                turns.startTurn(
                        id,
                        new SessionKey("audit", id.toString(), "a"),
                        "test",
                        "PRIVATE_PROMPT",
                        budget);
        budgets.register(id, budget);
        return new LlmCallRecord(
                turn.sessionId(),
                id,
                1,
                "primary",
                "test-model",
                null,
                null,
                CallMode.CALL,
                1,
                null,
                0,
                0,
                0,
                null,
                null,
                0L,
                null,
                null,
                null,
                null,
                true,
                null,
                null,
                null,
                Instant.now(),
                0);
    }

    @Test
    void retriesUseNewTransactionsAndStableWriteIdThenPersistOnce() {
        var r = record();
        var attempts = new AtomicInteger();
        var ids = new CopyOnWriteArrayList<UUID>();
        var transactions = new CopyOnWriteArrayList<Long>();
        doAnswer(
                        call -> {
                            var entity = call.getArgument(0, LlmCallEntity.class);
                            ids.add(entity.getWriteId());
                            transactions.add(
                                    jdbc.queryForObject("select txid_current()", Long.class));
                            if (attempts.incrementAndGet() < 3) {
                                throw new TransientDataAccessResourceException(
                                        "synthetic transient");
                            }
                            entityManager.persist(entity);
                            entityManager.flush();
                            return entity;
                        })
                .when(repository)
                .saveAndFlush(any(LlmCallEntity.class));
        recorder.record(r);
        assertThat(attempts).hasValue(3);
        assertThat(new HashSet<>(ids)).hasSize(1);
        assertThat(new HashSet<>(transactions)).hasSize(3);
        assertThat(repository.findAll().stream().filter(e -> e.getTurnId().equals(r.turnId())))
                .hasSize(1);
    }

    @Test
    void identicalSeparateRecordCallsAreSeparateActualRequests() {
        var r = record();
        recorder.record(r);
        recorder.record(r);
        assertThat(repository.findAll().stream().filter(e -> e.getTurnId().equals(r.turnId())))
                .hasSize(2);
    }

    @jakarta.annotation.Resource AuditTransactions transactions;
    @jakarta.annotation.Resource javax.sql.DataSource dataSource;
    @jakarta.annotation.Resource org.springframework.transaction.PlatformTransactionManager manager;

    @Test
    void committedResponseLossIsConfirmedWithoutSecondInsert() {
        var r = record();
        var commits = new AtomicInteger();
        var wrapped =
                new org.springframework.transaction.PlatformTransactionManager() {
                    public org.springframework.transaction.TransactionStatus getTransaction(
                            org.springframework.transaction.TransactionDefinition definition) {
                        return manager.getTransaction(definition);
                    }

                    public void rollback(org.springframework.transaction.TransactionStatus status) {
                        manager.rollback(status);
                    }

                    public void commit(org.springframework.transaction.TransactionStatus status) {
                        manager.commit(status);
                        if (commits.incrementAndGet() == 1) {
                            throw new TransientDataAccessResourceException(
                                    "synthetic response loss after real commit");
                        }
                    }
                };
        var recording =
                new JpaLlmCallRecorder(
                        repository,
                        new AuditTransactions(wrapped, dataSource),
                        budgets,
                        new com.agentos.core.tool.Redactor(List::of),
                        new com.agentos.core.config.AuditProperties(2, null, null, null));
        recording.record(r);
        verify(repository, times(1)).saveAndFlush(any(LlmCallEntity.class));
        assertThat(repository.findAll().stream().filter(e -> e.getTurnId().equals(r.turnId())))
                .hasSize(1);
    }

    @Test
    void unknownCommitWithDifferentPayloadIsNeverSilentlyConfirmed() {
        var r = record();
        var commits = new AtomicInteger();
        var wrapped =
                new org.springframework.transaction.PlatformTransactionManager() {
                    public org.springframework.transaction.TransactionStatus getTransaction(
                            org.springframework.transaction.TransactionDefinition definition) {
                        return manager.getTransaction(definition);
                    }

                    public void rollback(org.springframework.transaction.TransactionStatus status) {
                        manager.rollback(status);
                    }

                    public void commit(org.springframework.transaction.TransactionStatus status) {
                        manager.commit(status);
                        if (commits.incrementAndGet() == 1) {
                            jdbc.update(
                                    "update llm_calls set response_model='conflicting-payload' where turn_id=?",
                                    r.turnId());
                            throw new TransientDataAccessResourceException(
                                    "synthetic response loss");
                        }
                    }
                };
        var recording =
                new JpaLlmCallRecorder(
                        repository,
                        new AuditTransactions(wrapped, dataSource),
                        budgets,
                        new com.agentos.core.tool.Redactor(List::of),
                        new com.agentos.core.config.AuditProperties(2, null, null, null));
        assertThatThrownBy(() -> recording.record(r))
                .isInstanceOf(com.agentos.core.audit.AuditWriteException.class)
                .hasNoCause();
        verify(repository, times(1)).saveAndFlush(any(LlmCallEntity.class));
        assertThat(budgets.get(r.turnId()).auditsConfirmed()).isFalse();
    }

    @Test
    void successfulAndRolledBackTransactionsRestoreConnectionSettings() throws Exception {
        int originalTimeout;
        try (var connection = dataSource.getConnection()) {
            originalTimeout = connection.getNetworkTimeout();
        }
        assertThat(
                        transactions.execute(
                                Duration.ofSeconds(2),
                                () -> jdbc.queryForObject("select 1", Integer.class)))
                .isOne();
        var r = record();
        assertThatThrownBy(() ->
                        transactions.execute(
                                Duration.ofSeconds(2),
                                () -> {
                                    jdbc.update(
                                            "update agent_turns set iterations=99 where turn_id=?",
                                            r.turnId());
                                    throw new IllegalArgumentException("合成回滚");
                                }))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(
                        jdbc.queryForObject(
                                "select iterations from agent_turns where turn_id=?",
                                Integer.class,
                                r.turnId()))
                .isZero();
        // 同时借出全部连接，避免只检查到恰好未被上个事务使用的那一条。
        try (var first = dataSource.getConnection();
                var second = dataSource.getConnection()) {
            for (var connection : List.of(first, second)) {
                assertThat(connection.getAutoCommit()).isTrue();
                assertThat(connection.getNetworkTimeout()).isEqualTo(originalTimeout);
                try (var statement = connection.createStatement();
                        var rows = statement.executeQuery("show statement_timeout")) {
                    rows.next();
                    assertThat(rows.getString(1)).isEqualTo("0");
                }
                try (var statement = connection.createStatement();
                        var rows = statement.executeQuery("show lock_timeout")) {
                    rows.next();
                    assertThat(rows.getString(1)).isEqualTo("0");
                }
            }
        }
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from pg_stat_activity "
                                        + "where datname=current_database() and state like 'idle in transaction%'",
                                Integer.class))
                .isZero();
        recorder.record(r);
        assertThat(repository.findAll().stream().filter(row -> row.getTurnId().equals(r.turnId())))
                .hasSize(1);
    }

    @Test
    void finalAuditAfterDeadlineTriesOnceWithoutBackoffOrReplay() {
        var r = record();
        budgets.register(r.turnId(), TurnExecutionBudget.start(Duration.ofNanos(1)));
        doThrow(new TransientDataAccessResourceException("temporary"))
                .when(repository)
                .saveAndFlush(any());
        assertThatThrownBy(() -> recorder.record(r))
                .isInstanceOf(com.agentos.core.audit.AuditWriteException.class);
        verify(repository, times(1)).saveAndFlush(any(LlmCallEntity.class));
        assertThat(repository.findAll().stream().filter(e -> e.getTurnId().equals(r.turnId())))
                .isEmpty();
    }

    @Test
    void backoffHoldsNoConnectionAndFreshAuditWorksWithSmallPool() {
        var calls = new AtomicInteger();
        var pauses = new AtomicInteger();
        var writer =
                new com.agentos.core.audit.RetryingAuditWriter(
                        new com.agentos.core.config.AuditProperties(2, null, null, null),
                        AuditTransactions::transientFailure,
                        delay -> {
                            pauses.incrementAndGet();
                            assertThat(
                                            org.springframework.transaction.support
                                                    .TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            assertThat(
                                            ((com.zaxxer.hikari.HikariDataSource) dataSource)
                                                    .getHikariPoolMXBean()
                                                    .getActiveConnections())
                                    .isZero();
                            assertThat(jdbc.queryForObject("select 1", Integer.class)).isOne();
                        });
        writer.write(
                TurnExecutionBudget.start(Duration.ofSeconds(3)),
                false,
                new com.agentos.core.audit.RetryingAuditWriter.Operation<Void>() {
                    public Void write(Duration timeout) {
                        return transactions.execute(
                                timeout,
                                () -> {
                                    jdbc.queryForObject("select 1", Integer.class);
                                    if (calls.incrementAndGet() < 3) {
                                        throw new TransientDataAccessResourceException("temporary");
                                    }
                                    return null;
                                });
                    }

                    public boolean confirmed(Duration timeout) {
                        return transactions.execute(timeout, () -> false);
                    }
                },
                null);
        assertThat(calls).hasValue(3);
        assertThat(pauses).hasValue(2);
    }

    @Test
    void realSqlAndLockWaitsAreBoundedAndReleaseConnections() throws Exception {
        long started = System.nanoTime();
        assertThatThrownBy(() ->
                        transactions.execute(
                                Duration.ofMillis(100),
                                () -> jdbc.queryForObject("select pg_sleep(3)", String.class)))
                .isInstanceOf(RuntimeException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
        var r = record();
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement =
                    connection.prepareStatement(
                            "select * from agent_turns where turn_id=? for update")) {
                statement.setObject(1, r.turnId());
                statement.executeQuery().close();
            }
            started = System.nanoTime();
            assertThatThrownBy(() ->
                            transactions.execute(
                                    Duration.ofMillis(150),
                                    () ->
                                            jdbc.queryForObject(
                                                    "select status from agent_turns where turn_id=? for update",
                                                    String.class,
                                                    r.turnId())))
                    .isInstanceOf(RuntimeException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - started))
                    .isLessThan(Duration.ofSeconds(1));
            connection.rollback();
        }
        assertThat(jdbc.queryForObject("select 1", Integer.class)).isOne();
    }

    @Test
    void toolIntentRechecksDeadlineAfterWaitingForSessionLock() throws Exception {
        var record = record();
        jdbc.update(
                "update agent_turns set deadline_at=clock_timestamp()+interval '250 milliseconds' where turn_id=?",
                record.turnId());
        try (var holder = dataSource.getConnection();
                var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            holder.setAutoCommit(false);
            try {
                try (var lock =
                        holder.prepareStatement(
                                "select session_id from sessions where session_id=? for update")) {
                    lock.setObject(1, record.sessionId());
                    lock.executeQuery().close();
                }
                var pending =
                        executor.submit(() ->
                                transactions.execute(
                                        Duration.ofSeconds(5),
                                        () -> {
                                            transactions.requireRunning(
                                                    record.sessionId(), record.turnId());
                                            return null;
                                        }));
                org.awaitility.Awaitility.await()
                        .atMost(Duration.ofSeconds(2))
                        .until(() -> {
                            try (var statement = holder.createStatement();
                                    var rows =
                                            statement.executeQuery(
                                                    "select count(*) from pg_stat_activity where datname=current_database() and pid<>pg_backend_pid() and wait_event_type='Lock'")) {
                                rows.next();
                                return rows.getInt(1) > 0;
                            }
                        });
                try (var statement = holder.createStatement()) {
                    statement.execute("select pg_sleep(0.3)");
                }
                holder.commit();
                assertThatThrownBy(() -> pending.get(3, java.util.concurrent.TimeUnit.SECONDS))
                        .hasCauseInstanceOf(
                                com.agentos.core.turn.TurnDeadlineExceededException.class);
            } finally {
                holder.rollback();
            }
        }
    }

    @Test
    void poolExhaustionStopsWithinSharedBudgetWithoutLateWrite() throws Exception {
        var r = record();
        var budget = TurnExecutionBudget.start(Duration.ofMillis(150));
        budgets.register(r.turnId(), budget);
        try (var first = dataSource.getConnection();
                var second = dataSource.getConnection()) {
            long started = System.nanoTime();
            assertThatThrownBy(() -> recorder.record(r))
                    .isInstanceOf(com.agentos.core.audit.AuditWriteException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - started))
                    .isLessThan(Duration.ofSeconds(1));
            assertThat(budget.auditsConfirmed()).isFalse();
        }
        assertThat(repository.findAll().stream().filter(e -> e.getTurnId().equals(r.turnId())))
                .isEmpty();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(excludeName = "com.agentos.core.config.CoreAutoConfiguration")
    static class App {
        @Bean
        TurnProperties turnProperties() {
            return new TurnProperties(null, null, null);
        }

        @Bean
        TurnBudgetRegistry budgets() {
            return new TurnBudgetRegistry();
        }

        @Bean
        com.agentos.core.tool.Redactor redactor() {
            return new com.agentos.core.tool.Redactor(List::of);
        }
    }
}
