package com.agentos.boot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.agentos.boot.support.LocalModelServer;
import com.agentos.core.provider.LlmCallRecorder;
import com.agentos.core.turn.AgentService;
import com.agentos.core.turn.PersistenceStatus;
import com.agentos.core.turn.ProcessRequest;
import com.agentos.core.turn.TurnResult;
import com.agentos.core.turn.TurnStatus;
import com.agentos.storage.audit.JpaLlmCallRecorder;
import com.agentos.storage.session.LlmCallRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** 真实 Provider HTTP + PostgreSQL；只使用 localhost 模型替身和合成密钥。 */
@Tag("db")
@ExtendWith(BootDatabaseTestEnvironment.class)
@Testcontainers
@SpringBootTest(
        classes = AgentOSApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.config.import=",
            "spring.datasource.hikari.maximum-pool-size=2",
            "spring.datasource.hikari.connection-timeout=250"
        })
class LlmAuditIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    DockerImageName.parse("pgvector/pgvector:pg17")
                            .asCompatibleSubstituteFor("postgres"));

    static Path workspace;
    static LocalModelServer model;

    @BeforeAll
    static void prepare() throws Exception {
        workspace = Files.createTempDirectory("llm-audit");
        Files.createDirectories(workspace.resolve("agents/a"));
        Files.writeString(
                workspace.resolve("agents/a/AGENT.md"),
                "---\nname: a\nprovider:\n  model: test-model\n---\n简短回答。\n");
        model = LocalModelServer.start();
    }

    @AfterAll
    static void stop() {
        if (model != null) {
            model.close();
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("agentos.workspace.root", () -> workspace.toString());
        r.add("agentos.providers.primary.base-url", () -> model.baseUrl() + "/v1");
        r.add("agentos.providers.primary.api-key", () -> "audit-test-key");
        r.add("agentos.embedding.enabled", () -> false);
    }

    @jakarta.annotation.Resource AgentService service;
    @jakarta.annotation.Resource JdbcTemplate jdbc;
    @jakarta.annotation.Resource DataSource dataSource;
    @jakarta.annotation.Resource org.springframework.context.ApplicationContext context;
    @MockitoSpyBean LlmCallRepository calls;
    final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void capture() {
        appender.start();
        ((Logger) LoggerFactory.getLogger(JpaLlmCallRecorder.class)).addAppender(appender);
    }

    @AfterEach
    void resetState() {
        reset(calls);
        ((Logger) LoggerFactory.getLogger(JpaLlmCallRecorder.class)).detachAppender(appender);
        appender.stop();
    }

    TurnResult run() {
        return service.process(
                new ProcessRequest(
                        "audit",
                        UUID.randomUUID().toString(),
                        "a",
                        "PRIVATE_PROMPT audit-test-key",
                        "test"));
    }

    List<Map<String, Object>> rows(TurnResult result) {
        return jdbc.queryForList(
                "select * from llm_calls where turn_id=? order by attempt", result.turnId());
    }

    LocalModelServer.Reply failure(int status) {
        return new LocalModelServer.Reply(
                status,
                "{\"error\":{\"message\":\"PRIVATE_RESPONSE api_key=audit-test-key\",\"type\":\"test\"}}");
    }

    @Test
    void retry429PersistsFailureAndSuccessWithCorrelationAndNullableFields() {
        model.resetReplies(
                failure(429), new LocalModelServer.Reply(200, LocalModelServer.textResponse("ok")));
        var result = run();
        assertThat(result.status()).isEqualTo(TurnStatus.SUCCEEDED);
        assertThat(model.calls()).isEqualTo(2);
        var rows = rows(result);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0))
                .containsEntry("success", false)
                .containsEntry("http_status", 429)
                .containsEntry("attempt", 1)
                .containsEntry("iteration", 1);
        assertThat(rows.get(1))
                .containsEntry("success", true)
                .containsEntry("attempt", 2)
                .containsEntry("cache_write_tokens", null)
                .containsEntry("prompt_tokens", 1);
        assertThat(rows.get(0).get("session_id")).isEqualTo(rows.get(1).get("session_id"));
        assertThat(rows.get(0).get("write_id")).isNotEqualTo(rows.get(1).get("write_id"));
        assertSafeLogs(2, "CONFIRMED");
        assertThat(rows.toString())
                .doesNotContain("PRIVATE_PROMPT", "PRIVATE_RESPONSE", "audit-test-key");
    }

    @Test
    void finalProviderFailureKeepsBothFailedAttempts() {
        model.resetReplies(failure(503), failure(503));
        var result = run();
        assertThat(result.status()).isEqualTo(TurnStatus.FAILED);
        assertThat(model.calls()).isEqualTo(2);
        assertThat(rows(result))
                .hasSize(2)
                .allSatisfy(row -> assertThat(row.get("success")).isEqualTo(false));
        assertSafeLogs(2, "CONFIRMED");
    }

    @Test
    void recorderFailureNeverRetriesModelAndLogsUnconfirmedWithoutCause() {
        model.reset(LocalModelServer.textResponse("PRIVATE_RESPONSE"));
        doThrow(
                        new org.springframework.dao.DataIntegrityViolationException(
                                "password=audit-test-key PRIVATE_RESPONSE"))
                .when(calls)
                .saveAndFlush(any());
        var result = run();
        assertThat(result.status()).isEqualTo(TurnStatus.FAILED);
        assertThat(result.persistenceStatus()).isEqualTo(PersistenceStatus.UNCONFIRMED);
        assertThat(model.calls()).isOne();
        assertThat(rows(result)).isEmpty();
        verify(calls, times(1)).saveAndFlush(any());
        assertSafeLogs(1, "UNCONFIRMED");
        assertThat(appender.list.getFirst().getLevel())
                .isEqualTo(ch.qos.logback.classic.Level.ERROR);
        assertThat(appender.list.getFirst().getFormattedMessage())
                .contains(result.turnId().toString(), "attemptTimeoutMs=2000", "UNCONFIRMED")
                .doesNotContain("PRIVATE_PROMPT", "PRIVATE_RESPONSE", "audit-test-key");
    }

    @Test
    void failedAuditDoesNotPoisonNextTurnInSameSession() {
        String user = UUID.randomUUID().toString();
        model.reset(LocalModelServer.textResponse("first"));
        doThrow(
                        new org.springframework.dao.DataIntegrityViolationException(
                                "synthetic audit failure"))
                .when(calls)
                .saveAndFlush(any());
        var first = service.process(new ProcessRequest("audit", user, "a", "first", "test"));
        assertThat(first.status()).isEqualTo(TurnStatus.FAILED);
        assertThat(first.persistenceStatus()).isEqualTo(PersistenceStatus.UNCONFIRMED);
        assertThat(model.calls()).isOne();
        reset(calls);
        model.reset(LocalModelServer.textResponse("recovered"));
        var next = service.process(new ProcessRequest("audit", user, "a", "next", "test"));
        assertThat(next.status()).isEqualTo(TurnStatus.SUCCEEDED);
        assertThat(next.persistenceStatus()).isEqualTo(PersistenceStatus.CONFIRMED);
        assertThat(model.calls()).isOne();
        assertThat(rows(next)).hasSize(1);
        assertThat(
                        jdbc.queryForObject(
                                "select count(distinct session_id) from agent_turns where turn_id in (?,?)",
                                Integer.class,
                                first.turnId(),
                                next.turnId()))
                .isOne();
        assertThat(
                        jdbc.queryForList(
                                "select status from agent_turns where turn_id in (?,?) order by started_at",
                                String.class,
                                first.turnId(),
                                next.turnId()))
                .containsExactly("FAILED", "SUCCEEDED");
        assertThat(
                        jdbc.queryForObject(
                                "select lease_owner from sessions where session_id=(select session_id from agent_turns where turn_id=?)",
                                String.class,
                                next.turnId()))
                .isNull();
    }

    @Test
    void modelNetworkWaitHoldsNoDatabaseConnectionAndOnlyOneRecorderExists() {
        model.reset();
        var assertion = new AtomicReference<Throwable>();
        model.onRequest(() -> {
            try {
                assertThat(
                                ((com.zaxxer.hikari.HikariDataSource) dataSource)
                                        .getHikariPoolMXBean()
                                        .getActiveConnections())
                        .isZero();
                assertThat(
                                jdbc.queryForObject(
                                        "select count(*) from pg_stat_activity where datname=current_database() and state='idle in transaction'",
                                        Integer.class))
                        .isZero();
            } catch (Throwable error) {
                assertion.set(error);
            }
        });
        assertThat(run().status()).isEqualTo(TurnStatus.SUCCEEDED);
        assertThat(assertion.get()).isNull();
        assertThat(context.getBeansOfType(LlmCallRecorder.class))
                .hasSize(1)
                .allSatisfy(
                        (name, value) -> assertThat(value).isInstanceOf(JpaLlmCallRecorder.class));
        assertThat(context.getBeansOfType(com.agentos.storage.TurnReaperRunner.class)).isEmpty();
    }

    private void assertSafeLogs(int size, String persistence) {
        assertThat(appender.list).hasSize(size);
        for (var event : appender.list) {
            assertThat(event.getThrowableProxy()).isNull();
            assertThat(event.getKeyValuePairs()).hasSize(29);
            String metadata = event.getKeyValuePairs().toString();
            assertThat(metadata)
                    .contains("write_id", persistence, "cacheReadTokens", "outputCapHonored")
                    .doesNotContain("PRIVATE_PROMPT", "PRIVATE_RESPONSE", "audit-test-key");
            assertThat(event.getMDCPropertyMap().toString())
                    .doesNotContain("PRIVATE_PROMPT", "PRIVATE_RESPONSE", "audit-test-key");
        }
    }
}
