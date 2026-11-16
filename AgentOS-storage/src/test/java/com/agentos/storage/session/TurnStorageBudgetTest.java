package com.agentos.storage.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;

import com.agentos.core.session.SessionKey;
import com.agentos.core.session.TurnSnapshot;
import com.agentos.core.session.TurnStore;
import com.agentos.core.turn.TurnBudgetRegistry;
import com.agentos.core.turn.TurnExecutionBudget;
import com.agentos.core.turn.TurnRunner;
import com.agentos.storage.support.PostgresIntegrationTest;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** 普通存储有自己的操作预算；慢数据库不能被误判为轮次超时，也不能越过整轮截止时间。 */
@SpringBootTest(
        classes = TurnStorageBudgetTest.App.class,
        properties = {
            "spring.main.web-application-type=none",
            "spring.config.import=",
            "agentos.turn.storage-timeout=2s",
            "spring.datasource.hikari.maximum-pool-size=2"
        })
class TurnStorageBudgetTest extends PostgresIntegrationTest {
    @jakarta.annotation.Resource TurnStore turns;
    @jakarta.annotation.Resource JdbcTemplate jdbc;
    @jakarta.annotation.Resource TurnBudgetRegistry budgets;
    @jakarta.annotation.Resource javax.sql.DataSource dataSource;
    @jakarta.persistence.PersistenceContext jakarta.persistence.EntityManager entities;
    @MockitoSpyBean AgentTurnRepository repository;

    private SessionKey key() {
        return new SessionKey("storage-budget", UUID.randomUUID().toString(), "a");
    }

    private TurnSnapshot start(TurnExecutionBudget budget) {
        return turns.startTurn(UUID.randomUUID(), key(), "test", "hello", budget);
    }

    @Test
    void snapshotReadIsNotLimitedToAuditAttemptTimeout() {
        var turn = start(TurnExecutionBudget.start(Duration.ofSeconds(10)));
        // 延迟发生在真实事务和真实数据库内，不只在 Java 线程上 sleep。
        doAnswer(
                        call -> {
                            jdbc.execute("select pg_sleep(0.35)");
                            return java.util.Optional.ofNullable(
                                    entities.find(AgentTurnEntity.class, turn.turnId()));
                        })
                .when(repository)
                .findById(turn.turnId());
        assertThat(turns.find(turn.turnId()))
                .hasValueSatisfying(t -> assertThat(t.status()).isEqualTo("RUNNING"));
    }

    @Test
    void confirmationQueryStillUsesOriginalHardStop() {
        var turn = start(TurnExecutionBudget.start(Duration.ofSeconds(10)));
        doAnswer(
                        call -> {
                            jdbc.execute("select pg_sleep(2)");
                            return java.util.Optional.ofNullable(
                                    entities.find(AgentTurnEntity.class, turn.turnId()));
                        })
                .when(repository)
                .findById(turn.turnId());
        // 已取消的轮次至多剩一秒共享清理余量；查证不能重新拿到默认五秒。
        var budget = TurnExecutionBudget.start(Duration.ofSeconds(30));
        budget.cancel();
        long before = System.nanoTime();
        assertThatThrownBy(
                        () -> new TurnRunner().run(() -> turns.find(turn.turnId(), budget), budget))
                .isInstanceOf(RuntimeException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - before))
                .isLessThan(Duration.ofMillis(1300));
    }

    @Test
    void startStillRollsBackAtomicallyWhenRemainingTurnBudgetIsTooSmall() {
        var id = UUID.randomUUID();
        var key = key();
        delayTurnWrite("INSERT");
        try {
            var budget = TurnExecutionBudget.start(Duration.ofMillis(150));
            long before = System.nanoTime();
            assertThatThrownBy(() ->
                            new TurnRunner()
                                    .run(
                                            () -> turns.startTurn(id, key, "test", "hello", budget),
                                            budget))
                    .isInstanceOf(RuntimeException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - before))
                    .isLessThan(Duration.ofSeconds(1));
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from agent_turns where turn_id=?",
                                    Integer.class,
                                    id))
                    .isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from session_messages where turn_id=?",
                                    Integer.class,
                                    id))
                    .isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from sessions where user_id=?",
                                    Integer.class,
                                    key.userId()))
                    .isZero();
        } finally {
            dropDelay();
        }
    }

    @Test
    void afterDeadlineFinishKeepsSharedOneSecondCleanupLimit() {
        var budget = TurnExecutionBudget.start(Duration.ofMillis(300));
        var turn = start(budget);
        budgets.register(turn.turnId(), budget);
        delayTurnWrite("UPDATE");
        try {
            jdbc.execute("select pg_sleep(0.35)");
            long before = System.nanoTime();
            assertThatThrownBy(() ->
                            new TurnRunner()
                                    .run(
                                            () ->
                                                    turns.finishAndRelease(
                                                            turn, "TIMED_OUT", "timeout", null),
                                            budget))
                    .isInstanceOf(RuntimeException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - before))
                    .isLessThan(Duration.ofMillis(1200));
            assertThat(
                            jdbc.queryForObject(
                                    "select status from agent_turns where turn_id=?",
                                    String.class,
                                    turn.turnId()))
                    .isEqualTo("RUNNING");
            assertThat(
                            jdbc.queryForObject(
                                    "select lease_owner from sessions where session_id=?",
                                    String.class,
                                    turn.sessionId()))
                    .isEqualTo(turn.owner());
        } finally {
            dropDelay();
            budgets.remove(turn.turnId());
        }
    }

    @Test
    void connectionAcquisitionUsesStorageLimitWithoutLateLeaseRenewal() throws Exception {
        var budget = TurnExecutionBudget.start(Duration.ofSeconds(10));
        var turn = start(budget);
        var lease =
                jdbc.queryForObject(
                        "select lease_until from sessions where session_id=?",
                        java.sql.Timestamp.class,
                        turn.sessionId());
        try (var first = dataSource.getConnection();
                var second = dataSource.getConnection()) {
            long before = System.nanoTime();
            assertThatThrownBy(() ->
                            new TurnRunner().run(() -> turns.renewLease(turn, budget), budget))
                    .isInstanceOf(RuntimeException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - before))
                    .isLessThan(Duration.ofSeconds(3));
        }
        // 等被取消的借连接任务退出后再读回，避免仅用“马上查不到”作为无迟到写入的证据。
        var pool = ((com.zaxxer.hikari.HikariDataSource) dataSource).getHikariPoolMXBean();
        org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> {
                    assertThat(pool.getThreadsAwaitingConnection()).isZero();
                    assertThat(pool.getActiveConnections()).isZero();
                });
        assertThat(
                        jdbc.queryForObject(
                                "select lease_until from sessions where session_id=?",
                                java.sql.Timestamp.class,
                                turn.sessionId()))
                .isEqualTo(lease);
    }

    private void delayTurnWrite(String operation) {
        jdbc.execute(
                """
                CREATE FUNCTION turn_write_delay() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN PERFORM pg_sleep(1.1); RETURN NEW; END $$
                """);
        jdbc.execute(
                "CREATE TRIGGER turn_write_delay BEFORE "
                        + operation
                        + " ON agent_turns "
                        + "FOR EACH ROW EXECUTE FUNCTION turn_write_delay()");
    }

    private void dropDelay() {
        jdbc.execute("DROP TRIGGER turn_write_delay ON agent_turns");
        jdbc.execute("DROP FUNCTION turn_write_delay()");
    }

    @org.springframework.boot.SpringBootConfiguration
    @org.springframework.boot.autoconfigure.EnableAutoConfiguration(
            excludeName = "com.agentos.core.config.CoreAutoConfiguration")
    @org.springframework.boot.context.properties.EnableConfigurationProperties(
            com.agentos.core.config.TurnProperties.class)
    static class App {
        @org.springframework.context.annotation.Bean
        com.agentos.core.tool.Redactor redactor() {
            return new com.agentos.core.tool.Redactor(java.util.List::of);
        }
    }
}
