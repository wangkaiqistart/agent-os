package com.agentos.boot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.boot.support.LocalModelServer;
import com.agentos.core.error.AgentOSException;
import com.agentos.core.error.ErrorCode;
import com.agentos.core.session.MessageStore;
import com.agentos.core.session.SessionKey;
import com.agentos.core.session.SessionManager;
import com.agentos.core.session.TurnSnapshot;
import com.agentos.core.session.TurnStore;
import com.agentos.core.turn.AgentService;
import com.agentos.core.turn.PersistenceStatus;
import com.agentos.core.turn.ProcessRequest;
import com.agentos.core.turn.TurnExecutionBudget;
import com.agentos.core.turn.TurnStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** 每个竞争者独立调用存储事务，模型/工具闸门不会占用数据库连接。 */
@Tag("db")
@ExtendWith(BootDatabaseTestEnvironment.class)
@Testcontainers
@SpringBootTest(
        classes = AgentOSApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.main.web-application-type=none", "spring.config.import="})
class SessionConcurrencyIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    DockerImageName.parse("pgvector/pgvector:pg17")
                            .asCompatibleSubstituteFor("postgres"));

    static Path workspace;
    static LocalModelServer model;
    static com.sun.net.httpserver.HttpServer tool;
    static ExecutorService toolExecutor;
    static volatile Runnable toolAction = () -> {};
    @jakarta.annotation.Resource AgentService service;
    @jakarta.annotation.Resource TurnStore turns;
    @jakarta.annotation.Resource SessionManager sessions;
    @jakarta.annotation.Resource MessageStore messages;
    @jakarta.annotation.Resource JdbcTemplate jdbc;

    @BeforeAll
    static void prepare() throws Exception {
        workspace = Files.createTempDirectory("session-concurrency");
        Path agent = Files.createDirectories(workspace.resolve("agents/a"));
        Files.writeString(
                agent.resolve("AGENT.md"),
                "---\nname: a\nprovider:\n  model: test-model\ntools: [http_get]\n---\n测试助手\n");
        model = LocalModelServer.start();
        tool =
                com.sun.net.httpserver.HttpServer.create(
                        new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        toolExecutor = Executors.newVirtualThreadPerTaskExecutor();
        tool.setExecutor(toolExecutor);
        tool.createContext(
                "/tool",
                exchange -> {
                    try (exchange) {
                        toolAction.run();
                        byte[] body = "ok".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, body.length);
                        exchange.getResponseBody().write(body);
                    }
                });
        tool.start();
    }

    @AfterAll
    static void close() {
        if (model != null) {
            model.close();
        }
        if (tool != null) {
            tool.stop(0);
        }
        if (toolExecutor != null) {
            toolExecutor.shutdownNow();
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("agentos.workspace.root", () -> workspace.toString());
        r.add("agentos.providers.primary.base-url", () -> model.baseUrl() + "/v1");
        r.add("agentos.providers.primary.api-key", () -> "local-test-key");
        r.add("agentos.embedding.enabled", () -> false);
        r.add("agentos.tools.http.allowed-domains", () -> "127.0.0.1");
        r.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        r.add("spring.jpa.open-in-view", () -> false);
    }

    private ProcessRequest request(String user) {
        return new ProcessRequest("concurrent", user, "a", "hello", "test");
    }

    private SessionKey key(String user) {
        return new SessionKey("concurrent", user, "a");
    }

    private static void await(CountDownLatch gate) {
        try {
            assertThat(gate.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("测试闸门被中断");
        }
    }

    private TurnSnapshot takeover(String user) {
        var old = sessions.findActive(key(user)).orElseThrow();
        jdbc.update(
                "update sessions set lease_until=clock_timestamp()-interval '1 second' where session_id=?",
                old.sessionId());
        return turns.startTurn(
                UUID.randomUUID(),
                key(user),
                "test",
                "new owner",
                TurnExecutionBudget.start(Duration.ofSeconds(30)));
    }

    private void assertBusy(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        AgentOSException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SESSION_BUSY));
    }

    @RepeatedTest(20)
    void secondMessageAndNewAreReadOnlyWhileOtherSessionProgresses() throws Exception {
        model.reset();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var arrivals = new AtomicInteger();
        model.onRequest(() -> {
            if (arrivals.incrementAndGet() == 1) {
                entered.countDown();
                await(release);
            }
        });
        String user = UUID.randomUUID().toString();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> service.process(request(user)));
            try {
                await(entered);
                var session = sessions.findActive(key(user)).orElseThrow();
                var before =
                        jdbc.queryForMap(
                                "select * from sessions where session_id=?", session.sessionId());
                assertBusy(() -> service.process(request(user)));
                assertBusy(() -> sessions.archiveIfIdle(key(user)));
                assertThat(
                                jdbc.queryForMap(
                                        "select * from sessions where session_id=?",
                                        session.sessionId()))
                        .isEqualTo(before);
                assertThat(
                                jdbc.queryForObject(
                                        "select count(*) from agent_turns where session_id=?",
                                        Integer.class,
                                        session.sessionId()))
                        .isOne();
                assertThat(
                                jdbc.queryForObject(
                                        "select count(*) from session_messages where session_id=?",
                                        Integer.class,
                                        session.sessionId()))
                        .isOne();
                assertThat(
                                jdbc.queryForObject(
                                        "select count(*) from llm_calls where session_id=?",
                                        Integer.class,
                                        session.sessionId()))
                        .isZero();
                assertThat(service.process(request(UUID.randomUUID().toString())).status())
                        .isEqualTo(TurnStatus.SUCCEEDED);
                assertThat(first).isNotDone();
            } finally {
                release.countDown();
            }
            var result = first.get(10, TimeUnit.SECONDS);
            assertThat(result.status()).isEqualTo(TurnStatus.SUCCEEDED);
            assertThat(result.persistenceStatus()).isEqualTo(PersistenceStatus.CONFIRMED);
            assertThat(model.calls()).isEqualTo(2);
        }
    }

    @Test
    void providerRetryRemainsCoveredByWholeTurnLease() throws Exception {
        model.resetReplies(
                new LocalModelServer.Reply(
                        503, "{\"error\":{\"message\":\"retry\",\"type\":\"server_error\"}}"),
                new LocalModelServer.Reply(200, LocalModelServer.textResponse("after retry")));
        var first = new CountDownLatch(1);
        var firstRelease = new CountDownLatch(1);
        var second = new CountDownLatch(1);
        var secondRelease = new CountDownLatch(1);
        var arrivals = new AtomicInteger();
        model.onRequest(() -> {
            if (arrivals.incrementAndGet() == 1) {
                first.countDown();
                await(firstRelease);
            } else {
                second.countDown();
                await(secondRelease);
            }
        });
        String user = UUID.randomUUID().toString();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = executor.submit(() -> service.process(request(user)));
            try {
                await(first);
                var current = sessions.findActive(key(user)).orElseThrow();
                var deadline =
                        jdbc.queryForObject(
                                "select deadline_at from agent_turns where session_id=? and status='RUNNING'",
                                (rs, row) -> rs.getTimestamp(1).toInstant(),
                                current.sessionId());
                assertThat(current.leaseUntil())
                        .isAfterOrEqualTo(deadline.plusSeconds(30).minusMillis(1));
                assertBusy(() -> service.process(request(user)));
                firstRelease.countDown();
                await(second);
                assertBusy(() -> service.process(request(user)));
                assertThat(sessions.findActive(key(user)).orElseThrow().leaseUntil())
                        .isEqualTo(current.leaseUntil());
            } finally {
                firstRelease.countDown();
                secondRelease.countDown();
            }
            var result = pending.get(10, TimeUnit.SECONDS);
            assertThat(result.status()).isEqualTo(TurnStatus.SUCCEEDED);
            assertThat(result.persistenceStatus()).isEqualTo(PersistenceStatus.CONFIRMED);
            assertThat(model.calls()).isEqualTo(2);
            assertThat(
                            jdbc.queryForList(
                                    "select attempt from llm_calls where turn_id=? order by attempt",
                                    Integer.class,
                                    result.turnId()))
                    .containsExactly(1, 2);
        }
    }

    @Test
    void takeoverDuringModelPreventsLateMessagesAndPreservesNewLease() throws Exception {
        model.reset();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        model.onRequest(() -> {
            entered.countDown();
            await(release);
        });
        String user = UUID.randomUUID().toString();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var old = executor.submit(() -> service.process(request(user)));
            TurnSnapshot next;
            try {
                await(entered);
                next = takeover(user);
            } finally {
                release.countDown();
            }
            var result = old.get(10, TimeUnit.SECONDS);
            assertThat(result.status()).isEqualTo(TurnStatus.INTERRUPTED);
            assertThat(result.persistenceStatus()).isEqualTo(PersistenceStatus.CONFIRMED);
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from session_messages where turn_id=?",
                                    Integer.class,
                                    result.turnId()))
                    .isOne();
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from llm_calls where turn_id=?",
                                    Integer.class,
                                    result.turnId()))
                    .isOne();
            assertThat(sessions.findActive(key(user)).orElseThrow().leaseOwner())
                    .isEqualTo(next.owner());
            assertThat(model.calls()).isOne();
            turns.finishAndRelease(next, "SUCCEEDED", "new", null);
        }
    }

    @Test
    void takeoverDuringFirstToolStopsRemainingToolsAndNextModel() throws Exception {
        String url = "http://127.0.0.1:" + tool.getAddress().getPort() + "/tool";
        // 同一批两个工具；丢租约后第二个必须完全不执行。
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        var body = json.readTree(LocalModelServer.toolResponse("one", url));
        var calls =
                (tools.jackson.databind.node.ArrayNode) body.at("/choices/0/message/tool_calls");
        var second = calls.get(0).deepCopy();
        ((tools.jackson.databind.node.ObjectNode) second).put("id", "two");
        calls.add(second);
        model.reset(json.writeValueAsString(body));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executed = new AtomicInteger();
        toolAction =
                () -> {
                    executed.incrementAndGet();
                    entered.countDown();
                    await(release);
                };
        String user = UUID.randomUUID().toString();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var old = executor.submit(() -> service.process(request(user)));
            TurnSnapshot next;
            try {
                await(entered);
                next = takeover(user);
            } finally {
                release.countDown();
            }
            var result = old.get(10, TimeUnit.SECONDS);
            assertThat(result.status()).isEqualTo(TurnStatus.INTERRUPTED);
            assertThat(result.persistenceStatus()).isEqualTo(PersistenceStatus.CONFIRMED);
            assertThat(model.calls()).isOne();
            assertThat(executed).hasValue(1);
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from session_messages where turn_id=?",
                                    Integer.class,
                                    result.turnId()))
                    .isOne();
            assertThat(
                            jdbc.queryForList(
                                    "select status from tool_invocations where turn_id=?",
                                    String.class,
                                    result.turnId()))
                    .containsExactly("interrupted");
            assertThat(sessions.findActive(key(user)).orElseThrow().leaseOwner())
                    .isEqualTo(next.owner());
            turns.finishAndRelease(next, "SUCCEEDED", "new", null);
        } finally {
            toolAction = () -> {};
        }
    }
}
