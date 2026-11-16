package com.agentos.storage.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.core.config.TurnProperties;
import com.agentos.core.session.SessionKey;
import com.agentos.core.session.TurnSnapshot;
import com.agentos.core.session.TurnStore;
import com.agentos.core.turn.TurnExecutionBudget;
import com.agentos.storage.support.PostgresIntegrationTest;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/** 用独立 PostgreSQL 检验崩溃恢复，故意保留未来 deadline，不能等 deadline 到达才清理。 */
@SpringBootTest(
        classes = TurnReaperTest.App.class,
        properties = {"spring.main.web-application-type=none", "spring.config.import="})
class TurnReaperTest extends PostgresIntegrationTest {
    @jakarta.annotation.Resource TurnStore turns;
    @jakarta.annotation.Resource JdbcTemplate jdbc;

    private SessionKey key() {
        return new SessionKey("reaper", UUID.randomUUID().toString(), "a");
    }

    private TurnSnapshot start(SessionKey key) {
        return turns.startTurn(
                UUID.randomUUID(),
                key,
                "test",
                "hi",
                TurnExecutionBudget.start(Duration.ofSeconds(30)));
    }

    private void intent(TurnSnapshot t) {
        jdbc.update(
                "insert into tool_invocations(session_id,turn_id,iteration,tool_call_id,tool_name,tool_source,status,idempotent) values(?,?,1,'call','http_get','builtin','in_progress',true)",
                t.sessionId(),
                t.turnId());
    }

    private String tool(TurnSnapshot t) {
        return jdbc.queryForObject(
                "select status from tool_invocations where turn_id=?", String.class, t.turnId());
    }

    @Test
    void reapsLostOwnerAndOrphanToolsButLeavesEffectiveOwnerAndTerminalStatus() {
        var lost = start(key());
        intent(lost);
        jdbc.update(
                "update sessions set lease_owner='someone-else', lease_until=clock_timestamp()+interval '1 hour' where session_id=?",
                lost.sessionId());
        var live = start(key());
        intent(live);
        var failed = start(key());
        intent(failed);
        jdbc.update("update agent_turns set status='FAILED' where turn_id=?", failed.turnId());
        turns.reapInterrupted();
        assertThat(turns.find(lost.turnId()).orElseThrow().status()).isEqualTo("INTERRUPTED");
        assertThat(tool(lost)).isEqualTo("interrupted");
        assertThat(
                        jdbc.queryForObject(
                                "select lease_owner from sessions where session_id=?",
                                String.class,
                                lost.sessionId()))
                .isEqualTo("someone-else");
        assertThat(turns.find(live.turnId()).orElseThrow().status()).isEqualTo("RUNNING");
        assertThat(tool(live)).isEqualTo("in_progress");
        assertThat(turns.find(failed.turnId()).orElseThrow().status()).isEqualTo("FAILED");
        assertThat(tool(failed)).isEqualTo("interrupted");
        assertThat(turns.reapInterrupted()).isZero();
    }

    @Test
    void takeoverRecoversOldRoundAndFinishCannotOverwriteOrClearNewOwner() {
        var key = key();
        var old = start(key);
        intent(old);
        jdbc.update(
                "update sessions set lease_until=clock_timestamp()-interval '1 second' where session_id=?",
                old.sessionId());
        var next = start(key);
        assertThat(turns.find(old.turnId()).orElseThrow().status()).isEqualTo("INTERRUPTED");
        assertThat(tool(old)).isEqualTo("interrupted");
        var finish = turns.finishAndRelease(old, "SUCCEEDED", "迟到答案", null);
        assertThat(finish.status()).isEqualTo("INTERRUPTED");
        assertThat(finish.persistenceConfirmed()).isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "select lease_owner from sessions where session_id=?",
                                String.class,
                                next.sessionId()))
                .isEqualTo(next.owner());
    }

    @Test
    void ordinaryFinishAlsoClosesPendingToolsAtomically() {
        var t = start(key());
        intent(t);
        assertThat(turns.finishAndRelease(t, "FAILED", "处理失败", null).persistenceConfirmed())
                .isTrue();
        assertThat(tool(t)).isEqualTo("interrupted");
        assertThat(
                        jdbc.queryForObject(
                                "select lease_owner from sessions where session_id=?",
                                String.class,
                                t.sessionId()))
                .isNull();
    }

    @Test
    void completedHistoryDoesNotIssueToolUpdates() {
        var history = start(key());
        turns.finishAndRelease(history, "SUCCEEDED", "done", null);
        // 语句级触发器连“更新零行”也会拒绝，证明回收没有逐条触碰正常历史。
        recoveryTrigger("RAISE EXCEPTION '正常历史不应更新工具表';", "STATEMENT");
        try {
            assertThat(turns.reapInterrupted()).isZero();
        } finally {
            dropRecoveryTrigger();
        }
    }

    @Test
    void slowRecoveryUsesIndependentSessionTransactionsBeyondOneSecond() {
        var first = expiredWithTool();
        var second = expiredWithTool();
        // 真 PostgreSQL 写入延迟：每个会话都超过旧版的一秒，不能靠扩大同一全局事务通过。
        recoveryTrigger("PERFORM pg_sleep(1.1); RETURN NEW;", "ROW");
        try {
            long before = System.nanoTime();
            assertThat(turns.reapInterrupted()).isEqualTo(4);
            assertThat(Duration.ofNanos(System.nanoTime() - before))
                    .isGreaterThan(Duration.ofSeconds(2));
            assertThat(tool(first)).isEqualTo("interrupted");
            assertThat(tool(second)).isEqualTo("interrupted");
            assertThat(turns.reapInterrupted()).isZero();
        } finally {
            dropRecoveryTrigger();
        }
    }

    @Test
    void laterSessionFailureDoesNotRollBackEarlierRecovery() {
        var a = expiredWithTool();
        var b = expiredWithTool();
        // 与 PostgreSQL UUID 排序一致，明确让第二个会话写失败。
        var first = a.sessionId().toString().compareTo(b.sessionId().toString()) < 0 ? a : b;
        var second = first == a ? b : a;
        recoveryTrigger(
                "IF NEW.turn_id = '"
                        + second.turnId()
                        + "'::uuid THEN RAISE EXCEPTION '回收故障注入'; END IF; RETURN NEW;",
                "ROW");
        try {
            assertThatThrownBy(turns::reapInterrupted).isInstanceOf(RuntimeException.class);
            assertThat(tool(first)).isEqualTo("interrupted");
            assertThat(turns.find(first.turnId()).orElseThrow().status()).isEqualTo("INTERRUPTED");
            assertThat(tool(second)).isEqualTo("in_progress");
            assertThat(turns.find(second.turnId()).orElseThrow().status()).isEqualTo("RUNNING");
        } finally {
            dropRecoveryTrigger();
            turns.reapInterrupted();
        }
    }

    private TurnSnapshot expiredWithTool() {
        var turn = start(key());
        intent(turn);
        jdbc.update(
                "update sessions set lease_until=clock_timestamp()-interval '1 second' where session_id=?",
                turn.sessionId());
        return turn;
    }

    private void recoveryTrigger(String body, String level) {
        jdbc.execute(
                "CREATE FUNCTION recovery_probe() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                        + body
                        + " END $$");
        jdbc.execute(
                "CREATE TRIGGER recovery_probe BEFORE UPDATE ON tool_invocations FOR EACH "
                        + level
                        + " EXECUTE FUNCTION recovery_probe()");
    }

    private void dropRecoveryTrigger() {
        jdbc.execute("DROP TRIGGER recovery_probe ON tool_invocations");
        jdbc.execute("DROP FUNCTION recovery_probe()");
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(excludeName = "com.agentos.core.config.CoreAutoConfiguration")
    static class App {
        @Bean
        TurnProperties turnProperties() {
            return new TurnProperties(null, null, null);
        }

        @Bean
        com.agentos.core.tool.Redactor redactor() {
            return new com.agentos.core.tool.Redactor(java.util.List::of);
        }
    }
}
