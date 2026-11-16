package com.agentos.storage.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.core.audit.AuditWriteException;
import com.agentos.core.audit.ToolInvocationRecord;
import com.agentos.core.audit.ToolInvocationRecorder;
import com.agentos.core.config.TurnProperties;
import com.agentos.core.session.SessionKey;
import com.agentos.core.session.TurnSnapshot;
import com.agentos.core.session.TurnStore;
import com.agentos.core.tool.Redactor;
import com.agentos.core.turn.TurnExecutionBudget;
import com.agentos.storage.session.ToolInvocationRepository;
import com.agentos.storage.support.PostgresIntegrationTest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
        classes = JpaToolInvocationRecorderTest.App.class,
        properties = {"spring.main.web-application-type=none", "spring.config.import="})
class JpaToolInvocationRecorderTest extends PostgresIntegrationTest {
    @jakarta.annotation.Resource ToolInvocationRecorder recorder;
    @jakarta.annotation.Resource ToolInvocationRepository repository;
    @jakarta.annotation.Resource TurnStore turns;
    @jakarta.annotation.Resource JdbcTemplate jdbc;

    private ToolInvocationRecord record(TurnSnapshot t, String id, String input, String status) {
        return new ToolInvocationRecord(
                t.sessionId(),
                t.turnId(),
                1,
                id,
                "http_get",
                "builtin",
                input,
                status.equals("in_progress") ? null : "token=RESULT_SECRET",
                null,
                false,
                status,
                status.equals("failed") ? "password=ERROR_SECRET" : null,
                true,
                status.equals("in_progress") ? 0 : 1,
                status.equals("in_progress") ? null : 0L);
    }

    private TurnSnapshot turn() {
        return turns.startTurn(
                UUID.randomUUID(),
                new SessionKey("test", UUID.randomUUID().toString(), "a"),
                "test",
                "hi",
                TurnExecutionBudget.start(Duration.ofSeconds(10)));
    }

    @Test
    void beginThenCompleteUpdatesSameRowAndPreservesDefaultsAndJson() {
        var turn = turn();
        var begin =
                record(
                        turn,
                        "id",
                        "{\"token\":\"INPUT_SECRET\",\"nested\":[1,true]}",
                        "in_progress");
        recorder.begin(begin);
        recorder.begin(begin);
        var row =
                repository
                        .findByTurnIdAndIterationAndToolCallId(turn.turnId(), 1, "id")
                        .orElseThrow();
        assertThat(row.getStatus()).isEqualTo("in_progress");
        assertThat(row.getAttempts()).isZero();
        assertThat(row.getResultText()).isNull();
        assertThat(row.getResultChars()).isNull();
        assertThat(row.getErrorMessage()).isNull();
        assertThat(row.getDurationMs()).isNull();
        assertThat(row.isResultTruncated()).isFalse();
        assertThat(row.getCreatedAt()).isNotNull();
        assertThat(row.getUpdatedAt()).isEqualTo(row.getCreatedAt());
        assertThat(row.getInputJson()).isInstanceOf(Map.class);
        assertThat(row.getInputJson().toString()).doesNotContain("INPUT_SECRET").contains("nested");
        var end = record(turn, "id", begin.inputJson(), "failed");
        recorder.complete(end);
        recorder.complete(end);
        var done =
                repository
                        .findByTurnIdAndIterationAndToolCallId(turn.turnId(), 1, "id")
                        .orElseThrow();
        assertThat(done.getId()).isEqualTo(row.getId());
        assertThat(done.getCreatedAt()).isEqualTo(row.getCreatedAt());
        assertThat(done.getUpdatedAt()).isAfterOrEqualTo(row.getCreatedAt());
        assertThat(done.getAttempts()).isOne();
        assertThat(done.getStatus()).isEqualTo("failed");
        assertThat(done.getResultText()).doesNotContain("RESULT_SECRET");
        assertThat(done.getErrorMessage()).doesNotContain("ERROR_SECRET");
        assertThat(repository.findByTurnIdAndStatus(turn.turnId(), "failed")).hasSize(1);
        assertThatThrownBy(() -> recorder.complete(record(turn, "absent", "{}", "success")))
                .isInstanceOf(AuditWriteException.class);
        assertThatThrownBy(() -> recorder.complete(record(turn, "id", "{}", "success")))
                .isInstanceOf(AuditWriteException.class);
    }

    @Test
    void invalidJsonIsStoredAsJsonStringAndSkippedHasNoIntentRewrite() {
        var t = turn();
        recorder.begin(record(t, "broken", "not-json token=INPUT_SECRET", "in_progress"));
        var row =
                repository
                        .findByTurnIdAndIterationAndToolCallId(t.turnId(), 1, "broken")
                        .orElseThrow();
        assertThat(row.getInputJson()).isInstanceOf(String.class);
        assertThat(row.getInputJson().toString()).doesNotContain("INPUT_SECRET");
        var skipped =
                new ToolInvocationRecord(
                        t.sessionId(),
                        t.turnId(),
                        2,
                        "skip",
                        "http_get",
                        "builtin",
                        "{}",
                        null,
                        null,
                        false,
                        "limit_skipped",
                        null,
                        true,
                        0,
                        0L);
        recorder.skipped(skipped);
        var saved =
                repository
                        .findByTurnIdAndIterationAndToolCallId(t.turnId(), 2, "skip")
                        .orElseThrow();
        assertThat(saved.getStatus()).isEqualTo("limit_skipped");
        assertThat(saved.getAttempts()).isZero();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(excludeName = "com.agentos.core.config.CoreAutoConfiguration")
    static class App {
        /** 审计契约不加载 CoreAutoConfiguration，补齐 TurnStore 的默认时限依赖。 */
        @Bean
        TurnProperties turnProperties() {
            return new TurnProperties(null, null, null);
        }

        @Bean
        ToolInvocationRecorder testRecorder(
                ToolInvocationRepository repo,
                org.springframework.transaction.PlatformTransactionManager manager) {
            return new JpaToolInvocationRecorder(repo, new Redactor(List::of), manager);
        }
    }
}
