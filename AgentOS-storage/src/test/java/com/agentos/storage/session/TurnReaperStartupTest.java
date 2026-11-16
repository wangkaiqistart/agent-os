package com.agentos.storage.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentos.core.session.SessionKey;
import com.agentos.core.session.TurnStore;
import com.agentos.core.turn.TurnExecutionBudget;
import com.agentos.storage.TurnReaperRunner;
import com.agentos.storage.support.PostgresIntegrationTest;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/** 数据在 runner 创建之前存在，断言发生在 SpringApplication 已执行启动收尾之后。 */
@SpringBootTest(
        classes = {TurnReaperTest.App.class, TurnReaperStartupTest.Seed.class},
        properties = {
            "spring.main.web-application-type=none",
            "spring.config.import=",
            "agentos.runtime.chat=true"
        })
class TurnReaperStartupTest extends PostgresIntegrationTest {
    static final UUID ID = UUID.randomUUID();
    @jakarta.annotation.Resource TurnStore turns;
    @jakarta.annotation.Resource JdbcTemplate jdbc;
    @jakarta.annotation.Resource TurnReaperRunner runner;

    @Test
    void chatStartupRecoversSeededTurnAndToolsBeforeAcceptingMessages() {
        assertThat(runner).isNotNull();
        assertThat(turns.find(ID).orElseThrow().status()).isEqualTo("INTERRUPTED");
        assertThat(
                        jdbc.queryForObject(
                                "select status from tool_invocations where turn_id=?",
                                String.class,
                                ID))
                .isEqualTo("interrupted");
        assertThat(turns.reapInterrupted()).isZero();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Seed {
        @Bean
        static BeanFactoryPostProcessor seedBeforeRunner() {
            return factory ->
                    factory.getBeanDefinition("turnReaperRunner").setDependsOn("recoverySeed");
        }

        @Bean
        Object recoverySeed(TurnStore turns, JdbcTemplate jdbc) {
            var turn =
                    turns.startTurn(
                            ID,
                            new SessionKey("startup", ID.toString(), "a"),
                            "test",
                            "hello",
                            TurnExecutionBudget.start(Duration.ofSeconds(30)));
            jdbc.update(
                    "insert into tool_invocations(session_id,turn_id,iteration,tool_call_id,tool_name,tool_source,status,idempotent) values(?,?,1,'startup','http_get','builtin','in_progress',true)",
                    turn.sessionId(),
                    ID);
            jdbc.update(
                    "update sessions set lease_until=clock_timestamp()-interval '1 second' where session_id=?",
                    turn.sessionId());
            // 用真实 SQL 延迟覆盖用户遇到的启动路径：超过一秒仍应先完成回收再接受 chat。
            jdbc.execute(
                    """
                    CREATE FUNCTION startup_recovery_delay() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN PERFORM pg_sleep(1.1); RETURN NEW; END $$
                    """);
            jdbc.execute(
                    """
                    CREATE TRIGGER startup_recovery_delay BEFORE UPDATE ON tool_invocations
                    FOR EACH ROW EXECUTE FUNCTION startup_recovery_delay()
                    """);
            return new Object();
        }
    }
}
