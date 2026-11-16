package com.agentos.storage.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.core.audit.AuditWriteException;
import com.agentos.core.config.TurnProperties;
import com.agentos.core.provider.CallMode;
import com.agentos.core.provider.FailureKind;
import com.agentos.core.provider.LlmCallRecord;
import com.agentos.core.session.HistoryTurn;
import com.agentos.core.session.MessageStore;
import com.agentos.core.session.SessionKey;
import com.agentos.core.session.SessionManager;
import com.agentos.core.session.TurnSnapshot;
import com.agentos.core.session.TurnStore;
import com.agentos.core.turn.TurnExecutionBudget;
import com.agentos.storage.audit.JpaLlmCallRecorder;
import com.agentos.storage.support.PostgresIntegrationTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** JPA 端口和模型审计映射的最小 PostgreSQL 契约。 */
@SpringBootTest(
        classes = JpaStorageContractTest.TestApplication.class,
        properties = {"spring.main.web-application-type=none", "spring.config.import="})
class JpaStorageContractTest extends PostgresIntegrationTest {
    @jakarta.annotation.Resource private SessionManager sessions;
    @jakarta.annotation.Resource private TurnStore turns;
    @jakarta.annotation.Resource private MessageStore messages;
    @jakarta.annotation.Resource private com.agentos.core.turn.TurnBudgetRegistry budgets;
    @jakarta.annotation.Resource private JpaLlmCallRecorder recorder;
    @jakarta.annotation.Resource private LlmCallRepository llmCalls;
    @jakarta.annotation.Resource private JdbcTemplate jdbc;

    @Test
    void persistsTurnMessagesReadsSnapshotsAndDoesNotRewriteOldRows() {
        SessionKey key = key("basic");
        UUID turnId = UUID.randomUUID();
        TurnSnapshot turn =
                turns.startTurn(
                        turnId,
                        key,
                        "cli",
                        "hello",
                        TurnExecutionBudget.start(Duration.ofSeconds(10)));

        assertThat(sessions.findActive(key)).isPresent();
        // 当前 RUNNING 轮次不应重复注入下一次模型请求；只有收尾后才进入历史。
        assertThat(messages.recentTurns(sessions.findActive(key).orElseThrow(), 1)).isEmpty();

        messages.append(turn, 1, List.of(new AssistantMessage("answer")));
        long before = jdbc.queryForObject("select count(*) from session_messages", Long.class);
        assertThat(turns.finishAndRelease(turn, "SUCCEEDED", "answer", null).persistenceConfirmed())
                .isTrue();
        List<HistoryTurn> history = messages.recentTurns(sessions.findActive(key).orElseThrow(), 1);
        assertThat(history).hasSize(1);
        assertThat(history.getFirst().messages())
                .extracting(m -> m.getText())
                .containsExactly("hello", "answer");
        assertThat(jdbc.queryForObject("select count(*) from session_messages", Long.class))
                .isEqualTo(before);
        assertThat(turns.find(turnId))
                .hasValueSatisfying(
                        snapshot -> assertThat(snapshot.status()).isEqualTo("SUCCEEDED"));
    }

    @Test
    void mapsNullableZeroCacheAttemptImageCapFailureAndStatusFields() {
        SessionKey key = key("audit");
        TurnSnapshot turn =
                turns.startTurn(
                        UUID.randomUUID(),
                        key,
                        "cli",
                        "audit",
                        TurnExecutionBudget.start(Duration.ofSeconds(10)));
        Instant started = Instant.parse("2026-01-02T03:04:05Z");
        LlmCallRecord record =
                new LlmCallRecord(
                        turn.sessionId(),
                        turn.turnId(),
                        1,
                        "primary",
                        "model",
                        "model-response",
                        "response-id",
                        CallMode.STREAM,
                        2,
                        "length",
                        0,
                        0,
                        0,
                        0,
                        0,
                        0L,
                        null,
                        99L,
                        0L,
                        false,
                        false,
                        FailureKind.RATE_LIMITED,
                        429,
                        "rate limited",
                        started,
                        0);

        budgets.register(turn.turnId(), TurnExecutionBudget.start(Duration.ofSeconds(10)));
        recorder.record(record);
        LlmCallEntity saved =
                llmCalls.findAll().stream()
                        .filter(row -> row.getTurnId().equals(turn.turnId()))
                        .findFirst()
                        .orElseThrow();
        assertThat(saved.getMode()).isEqualTo(CallMode.STREAM);
        assertThat(saved.getAttempt()).isEqualTo(2);
        assertThat(saved.getImageCount()).isZero();
        assertThat(saved.getPromptTokens()).isZero();
        assertThat(saved.getCacheReadTokens()).isEqualTo(0L);
        assertThat(saved.getCacheWriteTokens()).isNull();
        assertThat(saved.getOutputCapHonored()).isFalse();
        assertThat(saved.getFailureKind()).isEqualTo(FailureKind.RATE_LIMITED);
        assertThat(saved.getHttpStatus()).isEqualTo(429);
        assertThat(saved.getDurationMs()).isZero();
    }

    @Test
    void rejectsAuditRecordsWithoutCorrelation() {
        LlmCallRecord record =
                new LlmCallRecord(
                        null,
                        UUID.randomUUID(),
                        1,
                        "primary",
                        "model",
                        null,
                        null,
                        CallMode.CALL,
                        1,
                        null,
                        null,
                        0,
                        null,
                        null,
                        null,
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
                        0);
        assertThatThrownBy(() -> recorder.record(record))
                .isInstanceOf(AuditWriteException.class)
                .hasMessageContaining("关联");
    }

    @Test
    void databaseClockIsAvailableForDeadlineAssertions() {
        Instant dbNow =
                jdbc.queryForObject(
                        "select clock_timestamp()", (rs, row) -> rs.getTimestamp(1).toInstant());
        assertThat(dbNow).isNotNull();
        assertThat(dbNow).isAfter(Instant.now().minusSeconds(5));
        assertThat(dbNow).isBefore(Instant.now().plusSeconds(5));
    }

    @Test
    void archiveStartsFreshSessionWithoutChangingOldMessages() {
        SessionKey key = key("new");
        TurnSnapshot first =
                turns.startTurn(
                        UUID.randomUUID(),
                        key,
                        "cli",
                        "old question",
                        TurnExecutionBudget.start(Duration.ofSeconds(10)));
        messages.append(first, 1, List.of(new AssistantMessage("old answer")));
        turns.finishAndRelease(first, "SUCCEEDED", "old answer", null);
        long oldMessageCount =
                jdbc.queryForObject(
                        "select count(*) from session_messages where session_id = ?",
                        Long.class,
                        first.sessionId());

        assertThat(sessions.archiveIfIdle(key)).isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "select status from sessions where session_id = ?",
                                String.class,
                                first.sessionId()))
                .isEqualTo("archived");

        TurnSnapshot second =
                turns.startTurn(
                        UUID.randomUUID(),
                        key,
                        "cli",
                        "new question",
                        TurnExecutionBudget.start(Duration.ofSeconds(10)));
        assertThat(second.sessionId()).isNotEqualTo(first.sessionId());
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from session_messages where session_id = ?",
                                Long.class,
                                first.sessionId()))
                .isEqualTo(oldMessageCount);
        assertThat(messages.recentTurns(sessions.findActive(key).orElseThrow(), 20)).isEmpty();
    }

    @Test
    void renewLeaseWaitsForSessionRowLockAndRejectsLeaseExpiredWhileBlocked() throws Exception {
        SessionKey key = key("lock");
        TurnSnapshot turn =
                turns.startTurn(
                        UUID.randomUUID(),
                        key,
                        "cli",
                        "lock",
                        TurnExecutionBudget.start(Duration.ofSeconds(10)));
        Connection locker = jdbc.getDataSource().getConnection();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            locker.setAutoCommit(false);
            try (PreparedStatement expire =
                    locker.prepareStatement(
                            "update sessions set lease_until = clock_timestamp() - interval '1 second' where session_id = ?")) {
                expire.setObject(1, turn.sessionId());
                assertThat(expire.executeUpdate()).isEqualTo(1);
            }
            try (PreparedStatement lock =
                    locker.prepareStatement(
                            "select session_id from sessions where session_id = ? for update")) {
                lock.setObject(1, turn.sessionId());
                lock.executeQuery().close();
            }

            Future<Boolean> renewal =
                    executor.submit(() ->
                            turns.renewLease(
                                    turn, TurnExecutionBudget.start(Duration.ofSeconds(10))));
            Thread.sleep(250);
            assertThat(renewal).isNotDone();

            locker.commit();
            assertThat(renewal.get(5, TimeUnit.SECONDS)).isFalse();
        } finally {
            if (!locker.getAutoCommit()) {
                locker.rollback();
            }
            locker.close();
            executor.shutdownNow();
        }
    }

    private SessionKey key(String suffix) {
        return new SessionKey("jpa", "t022-" + suffix + "-" + UUID.randomUUID(), "agent");
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(excludeName = "com.agentos.core.config.CoreAutoConfiguration")
    @EntityScan("com.agentos.storage.session")
    static class TestApplication {
        /** 存储契约隔离了 CoreAutoConfiguration，显式提供 TurnStore 所需的默认时限。 */
        @org.springframework.context.annotation.Bean
        TurnProperties turnProperties() {
            return new TurnProperties(null, null, null);
        }
    }
}
