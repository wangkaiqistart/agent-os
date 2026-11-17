package com.agentos.storage.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.core.audit.AuditWriteException;
import com.agentos.core.audit.ToolInvocationRecord;
import com.agentos.core.audit.ToolInvocationRecorder;
import com.agentos.core.config.TurnProperties;
import com.agentos.core.provider.CallMode;
import com.agentos.core.provider.LlmCallRecord;
import com.agentos.core.provider.LlmCallRecorder;
import com.agentos.core.session.SessionKey;
import com.agentos.core.session.TurnStore;
import com.agentos.core.tool.Redactor;
import com.agentos.core.turn.TurnBudgetRegistry;
import com.agentos.core.turn.TurnExecutionBudget;
import com.agentos.storage.support.PostgresIntegrationTest;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/** 真实 PostgreSQL 上逐次 SQL/提交注入往返等待；不连接用户的远程数据库。 */
@SpringBootTest(
        classes = AuditLatencyTest.App.class,
        properties = {"spring.main.web-application-type=none", "spring.config.import="})
class AuditLatencyTest extends PostgresIntegrationTest {
    @jakarta.annotation.Resource LlmCallRecorder recorder;
    @jakarta.annotation.Resource ToolInvocationRecorder tools;
    @jakarta.annotation.Resource TurnStore turns;
    @jakarta.annotation.Resource TurnBudgetRegistry budgets;
    @jakarta.annotation.Resource JdbcTemplate jdbc;
    @jakarta.annotation.Resource LatencyDataSource dataSource;
    @jakarta.annotation.Resource AuditTransactions transactions;

    @AfterEach
    void resetLatency() {
        dataSource.delayMillis = 0;
        dataSource.commitResponseDelay.set(0);
    }

    private LlmCallRecord record() {
        var id = UUID.randomUUID();
        var budget = TurnExecutionBudget.start(Duration.ofSeconds(30));
        var turn =
                turns.startTurn(
                        id,
                        new SessionKey("latency", id.toString(), "a"),
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
                1,
                1,
                2,
                null,
                null,
                null,
                null,
                null,
                true,
                null,
                null,
                null,
                Instant.now(),
                1);
    }

    @Test
    void consecutiveAuditsSurviveSixtyMillisecondDatabaseRoundTrips() {
        recorder.record(record()); // 先初始化实体/驱动路径；被测请求仍实际走完整事务。
        var record = record();
        try {
            for (int delay : List.of(60, 65, 60)) {
                dataSource.delayMillis = delay;
                recorder.record(record);
            }
        } finally {
            dataSource.delayMillis = 0;
        }
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from llm_calls where turn_id=?",
                                Integer.class,
                                record.turnId()))
                .isEqualTo(3);
        assertThat(
                        jdbc.queryForObject(
                                "select count(distinct write_id) from llm_calls where turn_id=?",
                                Integer.class,
                                record.turnId()))
                .isEqualTo(3);
        assertThat(budgets.get(record.turnId()).auditsConfirmed()).isTrue();
    }

    @Test
    void remoteRoundTripsAndCommitJitterDoNotBreakConsecutiveAudits() {
        recorder.record(record());
        var record = record();
        dataSource.insertAttempts.set(0);
        try {
            for (int delay : List.of(150, 250, 180)) {
                dataSource.delayMillis = delay;
                recorder.record(record);
            }
        } finally {
            dataSource.delayMillis = 0;
        }
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from llm_calls where turn_id=?",
                                Integer.class,
                                record.turnId()))
                .isEqualTo(3);
        assertThat(dataSource.insertAttempts).hasValue(3);
        assertThat(budgets.get(record.turnId()).auditsConfirmed()).isTrue();
        assertThat(dataSource.pool.getHikariPoolMXBean().getActiveConnections()).isZero();
    }

    @Test
    void commitResponsePastWaitLimitIsConfirmedOnceUsingAnotherConnection() {
        recorder.record(record());
        var record = record();
        dataSource.insertAttempts.set(0);
        dataSource.commitResponseDelay.set(2300);
        recorder.record(record);
        assertThat(dataSource.insertAttempts).hasValue(1);
        assertThat(budgets.get(record.turnId()).auditsConfirmed()).isTrue();
        org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(3))
                .untilAsserted(() ->
                        assertThat(dataSource.pool.getHikariPoolMXBean().getActiveConnections())
                                .isZero());
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from llm_calls where turn_id=?",
                                Integer.class,
                                record.turnId()))
                .isOne();
        // 同一连接池继续完成下一条审计，不能残留超时配置或未结束事务。
        recorder.record(record);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from llm_calls where turn_id=?",
                                Integer.class,
                                record.turnId()))
                .isEqualTo(2);
    }

    @Test
    void slowTimeoutConfigurationCannotStartBusinessWriteAfterAttemptExpiry() {
        var invoked = new java.util.concurrent.atomic.AtomicBoolean();
        dataSource.delayMillis = 200;
        try {
            assertThatThrownBy(() ->
                            transactions.execute(
                                    Duration.ofMillis(100),
                                    () -> {
                                        invoked.set(true);
                                        return null;
                                    }))
                    .isInstanceOf(AuditWriteException.class);
        } finally {
            dataSource.delayMillis = 0;
        }
        assertThat(invoked).isFalse();
        assertThat(jdbc.queryForObject("select 1", Integer.class)).isOne();
    }

    private ToolInvocationRecord toolRecord(LlmCallRecord turn, String id, String status) {
        boolean intent = status.equals("in_progress");
        boolean skipped = status.equals("limit_skipped");
        return new ToolInvocationRecord(
                turn.sessionId(),
                turn.turnId(),
                1,
                id,
                "http_get",
                "builtin",
                "{}",
                intent || skipped ? null : "ok",
                intent || skipped ? null : 2,
                false,
                status,
                null,
                true,
                intent || skipped ? 0 : 1,
                intent ? null : skipped ? 0L : 1L);
    }

    @Test
    void toolIntentCompletionAndSkippedAuditSurviveFiftyMillisecondRoundTrips() {
        var warm = record();
        tools.begin(toolRecord(warm, "warm", "in_progress"));
        tools.complete(toolRecord(warm, "warm", "success"));
        var turn = record();
        try {
            dataSource.delayMillis = 50;
            tools.begin(toolRecord(turn, "call", "in_progress"));
            tools.complete(toolRecord(turn, "call", "success"));
            tools.skipped(toolRecord(turn, "skip", "limit_skipped"));
        } finally {
            dataSource.delayMillis = 0;
        }
        assertThat(
                        jdbc.queryForList(
                                "select status from tool_invocations where turn_id=? order by tool_call_id",
                                String.class,
                                turn.turnId()))
                .containsExactly("success", "limit_skipped");
        assertThat(budgets.get(turn.turnId()).auditsConfirmed()).isTrue();
    }

    @Test
    void latencyBeyondAttemptBudgetStillFailsClosedWithoutInsertReplay() {
        recorder.record(record());
        var record = record();
        dataSource.insertAttempts.set(0);
        dataSource.delayMillis = 800;
        long before = System.nanoTime();
        try {
            assertThatThrownBy(() -> recorder.record(record))
                    .isInstanceOf(AuditWriteException.class)
                    .hasNoCause();
            assertThat(Duration.ofNanos(System.nanoTime() - before))
                    .isLessThan(Duration.ofSeconds(5));
            assertThat(budgets.get(record.turnId()).auditsConfirmed()).isFalse();
            assertThat(dataSource.insertAttempts.get()).isLessThanOrEqualTo(1);
        } finally {
            dataSource.delayMillis = 0;
        }
        org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(2))
                .untilAsserted(() ->
                        assertThat(dataSource.pool.getHikariPoolMXBean().getActiveConnections())
                                .isZero());
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from llm_calls where turn_id=?",
                                Integer.class,
                                record.turnId()))
                .isLessThanOrEqualTo(1);
    }

    /** 只模拟客户端往返时间，SQL、事务、连接池、提交和回读都由真实 PostgreSQL 完成。 */
    static final class LatencyDataSource extends DelegatingDataSource implements AutoCloseable {
        private final HikariDataSource pool;
        private volatile int delayMillis;
        private final AtomicInteger insertAttempts = new AtomicInteger();
        private final AtomicInteger commitResponseDelay = new AtomicInteger();

        LatencyDataSource(HikariDataSource pool) {
            super(pool);
            this.pool = pool;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return connection(super.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return connection(super.getConnection(username, password));
        }

        private Connection connection(Connection target) {
            return (Connection)
                    Proxy.newProxyInstance(
                            Connection.class.getClassLoader(),
                            new Class<?>[] {Connection.class},
                            (proxy, method, args) -> {
                                if (method.getName().equals("commit")) {
                                    delay();
                                }
                                Object value = invoke(target, method, args);
                                if (method.getName().equals("commit")) {
                                    // 真实提交完成后才延迟客户端确认；另一个事务可以看到已提交的行。
                                    TimeUnit.MILLISECONDS.sleep(commitResponseDelay.getAndSet(0));
                                }
                                if (value instanceof Statement statement) {
                                    String sql =
                                            args != null
                                                            && args.length > 0
                                                            && args[0] instanceof String s
                                                    ? s
                                                    : "";
                                    Class<?> type =
                                            value instanceof java.sql.PreparedStatement
                                                    ? java.sql.PreparedStatement.class
                                                    : Statement.class;
                                    return Proxy.newProxyInstance(
                                            type.getClassLoader(),
                                            new Class<?>[] {type},
                                            (p, m, a) -> {
                                                if (m.getName().startsWith("execute")) {
                                                    if (sql.toLowerCase(java.util.Locale.ROOT)
                                                            .startsWith("insert into llm_calls")) {
                                                        insertAttempts.incrementAndGet();
                                                    }
                                                    delay();
                                                }
                                                return invoke(statement, m, a);
                                            });
                                }
                                return value;
                            });
        }

        private void delay() throws SQLException {
            try {
                TimeUnit.MILLISECONDS.sleep(delayMillis);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new SQLException("模拟往返等待被取消", "08006");
            }
        }

        private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        }

        @Override
        public void close() {
            pool.close();
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(excludeName = "com.agentos.core.config.CoreAutoConfiguration")
    static class App {
        @Bean
        LatencyDataSource dataSource(Environment env) {
            var config = new HikariConfig();
            config.setJdbcUrl(env.getRequiredProperty("spring.datasource.url"));
            config.setUsername(env.getRequiredProperty("spring.datasource.username"));
            config.setPassword(env.getRequiredProperty("spring.datasource.password"));
            config.setMaximumPoolSize(2);
            return new LatencyDataSource(new HikariDataSource(config));
        }

        @Bean
        TurnProperties turnProperties() {
            return new TurnProperties(null, null, null);
        }

        @Bean
        Redactor redactor() {
            return new Redactor(List::of);
        }
    }
}
