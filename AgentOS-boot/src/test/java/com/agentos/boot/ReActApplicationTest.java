package com.agentos.boot;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentos.boot.support.LocalModelServer;
import com.agentos.core.provider.LlmCallRecorder;
import com.agentos.core.turn.AgentService;
import com.agentos.core.turn.PersistenceStatus;
import com.agentos.core.turn.ProcessRequest;
import com.agentos.core.turn.TurnStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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

/** Boot、Provider、Storage、Core 和本地模型替身组成的最小纯文本纵向验收。 */
@Tag("db")
@ExtendWith(BootDatabaseTestEnvironment.class)
@Testcontainers
@SpringBootTest(
        classes = AgentOSApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.main.web-application-type=none", "spring.config.import="})
class ReActApplicationTest {
    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    DockerImageName.parse("pgvector/pgvector:pg17")
                            .asCompatibleSubstituteFor("postgres"));

    private static Path workspace;
    private static LocalModelServer model;
    private static com.sun.net.httpserver.HttpServer weather;
    private static java.util.concurrent.ExecutorService weatherExecutor;
    private static final java.util.concurrent.atomic.AtomicInteger weatherCalls =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicReference<Runnable> beforeHttp =
            new java.util.concurrent.atomic.AtomicReference<>(() -> {});

    @BeforeAll
    static void prepareWorkspace() throws Exception {
        workspace = Files.createTempDirectory("agentos-boot");
        Path agent = workspace.resolve("agents/answer");
        Files.createDirectories(agent);
        Files.writeString(
                agent.resolve("AGENT.md"),
                "---\nname: answer\nprovider:\n  model: test-model\n---\nAnswer briefly.\n");
        Path toolAgent = workspace.resolve("agents/weather");
        Files.createDirectories(toolAgent);
        Files.writeString(
                toolAgent.resolve("AGENT.md"),
                "---\nname: weather\nprovider:\n  model: test-model\ntools: [http_get]\n---\n查询天气。\n");
        Path boundedAgent = workspace.resolve("agents/bounded");
        Files.createDirectories(boundedAgent);
        Files.writeString(
                boundedAgent.resolve("AGENT.md"),
                "---\nname: bounded\nprovider:\n  model: test-model\ntools: [http_get]\nsettings:\n  max_iterations: 1\n  turn_timeout: 5s\n---\n只调用一次工具。\n");
        Path deadlineAgent = workspace.resolve("agents/deadline");
        Files.createDirectories(deadlineAgent);
        Files.writeString(
                deadlineAgent.resolve("AGENT.md"),
                // HTTP 夹具在本轮结束前不返回响应，不能依赖数据库/网络额外开销来耗尽预算。
                "---\nname: deadline\nprovider:\n  model: test-model\ntools: [http_get]\nsettings:\n  turn_timeout: 750ms\n---\n调用工具。\n");
        model = LocalModelServer.start();
        weather =
                com.sun.net.httpserver.HttpServer.create(
                        new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        weatherExecutor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        weather.setExecutor(weatherExecutor);
        weather.createContext(
                "/weather",
                exchange -> {
                    try (exchange) {
                        weatherCalls.incrementAndGet();
                        beforeHttp.get().run();
                        byte[] body =
                                "{\"weather\":\"晴\",\"token\":\"RESPONSE_SECRET\",\"note\":\"local-test-key\"}"
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, body.length);
                        exchange.getResponseBody().write(body);
                    }
                });
        weather.start();
    }

    @AfterAll
    static void stopModel() {
        if (weather != null) {
            weather.stop(0);
        }
        if (weatherExecutor != null) {
            weatherExecutor.shutdownNow();
        }
        if (model != null) {
            model.close();
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.open-in-view", () -> false);
        registry.add("agentos.workspace.root", () -> workspace.toString());
        registry.add("agentos.providers.primary.base-url", () -> model.baseUrl() + "/v1");
        registry.add("agentos.providers.primary.api-key", () -> "local-test-key");
        registry.add("agentos.embedding.enabled", () -> false);
        registry.add("agentos.tools.http.allowed-domains", () -> "127.0.0.1");
    }

    @Test
    void plainTextChatPersistsSessionTurnMessagesAndOneModelCall(
            org.springframework.context.ApplicationContext context) {
        model.reset();
        AgentService service = context.getBean(AgentService.class);
        var result =
                service.process(new ProcessRequest("cli", "boot-user", "answer", "hello", "cli"));

        assertThat(result.status()).isEqualTo(TurnStatus.SUCCEEDED);
        assertThat(result.text()).isEqualTo("local answer");
        assertThat(result.persistenceStatus()).isEqualTo(PersistenceStatus.CONFIRMED);
        assertThat(model.calls()).isOne();
        assertThat(model.requests().getFirst()).doesNotContain("\"tools\"");
        assertThat(context.getBeansOfType(LlmCallRecorder.class)).hasSize(1);
        assertThat(context.getBeansOfType(org.springframework.ai.chat.model.ChatModel.class))
                .isEmpty();
        assertThat(context.getBeansOfType(org.springframework.ai.embedding.EmbeddingModel.class))
                .isEmpty();
        // 既有本地调试入口必须默认关闭，不能冒充本 story 的正式 REST/SSE API。
        assertThat(context.getBeansOfType(com.agentos.web.dev.AgentLoopDebugController.class))
                .isEmpty();

        JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from session_messages where turn_id=?",
                                Integer.class,
                                result.turnId()))
                .isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from llm_calls where turn_id=?",
                                Integer.class,
                                result.turnId()))
                .isEqualTo(1);
        assertThat(jdbc.queryForMap("select * from llm_calls where turn_id=?", result.turnId()))
                .doesNotContainKey("prompt");
    }

    @Test
    @org.junit.jupiter.api.extension.ExtendWith(
            org.springframework.boot.test.system.OutputCaptureExtension.class)
    void toolRoundUsesTwoModelsOneHttpFourMessagesAndRedactedAudit(
            org.springframework.context.ApplicationContext context,
            org.springframework.boot.test.system.CapturedOutput output) {
        var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        String id = "weather-call";
        model.reset(
                LocalModelServer.toolResponse(
                        id,
                        "http://127.0.0.1:"
                                + weather.getAddress().getPort()
                                + "/weather?token=URL_SECRET"),
                LocalModelServer.textResponse("北京天气晴，建议穿薄外套。"));
        weatherCalls.set(0);
        beforeHttp.set(() -> {
            assertThat(
                            ((com.zaxxer.hikari.HikariDataSource) context.getBean(DataSource.class))
                                    .getHikariPoolMXBean()
                                    .getActiveConnections())
                    .isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from tool_invocations where tool_call_id=? and status='in_progress' and attempts=0",
                                    Integer.class,
                                    id))
                    .isOne();
        });
        var result =
                context.getBean(AgentService.class)
                        .process(
                                new ProcessRequest(
                                        "test", "weather-user", "weather", "天气怎样", "test"));
        beforeHttp.set(() -> {});
        assertThat(result.status()).isEqualTo(TurnStatus.SUCCEEDED);
        assertThat(result.persistenceStatus()).isEqualTo(PersistenceStatus.CONFIRMED);
        assertThat(result.iterations()).isEqualTo(2);
        assertThat(result.text()).contains("薄外套");
        assertThat(
                        jdbc.queryForObject(
                                "select iterations from agent_turns where turn_id=?",
                                Integer.class,
                                result.turnId()))
                .isEqualTo(2);
        assertThat(model.calls()).isEqualTo(2);
        assertThat(weatherCalls).hasValue(1);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from session_messages where turn_id=?",
                                Integer.class,
                                result.turnId()))
                .isEqualTo(4);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from llm_calls where turn_id=?",
                                Integer.class,
                                result.turnId()))
                .isEqualTo(2);
        var messages =
                jdbc.queryForList(
                        "select type,tool_calls::text as calls,tool_responses::text as responses from session_messages where turn_id=? order by id",
                        result.turnId());
        assertThat(messages)
                .extracting(r -> r.get("type"))
                .containsExactly("USER", "ASSISTANT", "TOOL", "ASSISTANT");
        assertThat(messages.get(1).get("calls").toString()).contains(id);
        assertThat(messages.get(1).get("calls").toString())
                .doesNotContain("URL_SECRET", "local-test-key");
        assertThat(messages.get(2).get("responses").toString())
                .contains(id)
                .doesNotContain("URL_SECRET", "RESPONSE_SECRET", "local-test-key");
        var audit =
                jdbc.queryForMap("select * from tool_invocations where turn_id=?", result.turnId());
        assertThat(audit)
                .containsEntry("status", "success")
                .containsEntry("tool_call_id", id)
                .containsEntry("iteration", 1)
                .containsEntry("tool_source", "builtin");
        assertThat(audit.toString())
                .doesNotContain("URL_SECRET", "RESPONSE_SECRET", "local-test-key");
        assertThat(model.requests().get(1))
                .contains(id, "晴")
                .doesNotContain("URL_SECRET", "RESPONSE_SECRET", "local-test-key");
        assertThat(output.getAll())
                .doesNotContain("URL_SECRET", "RESPONSE_SECRET", "local-test-key");
        model.reset(LocalModelServer.textResponse("继续回答"));
        var resumed =
                context.getBean(AgentService.class)
                        .process(
                                new ProcessRequest(
                                        "test", "weather-user", "weather", "继续", "test"));
        assertThat(resumed.status()).isEqualTo(TurnStatus.SUCCEEDED);
        assertThat(model.requests().getFirst())
                .contains(id)
                .doesNotContain("URL_SECRET", "RESPONSE_SECRET", "local-test-key");
    }

    @Test
    void tenConsecutiveTurnsKeepHistoryAndConfirmEveryAudit(
            org.springframework.context.ApplicationContext context) {
        var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        String user = java.util.UUID.randomUUID().toString();
        java.util.UUID session = null;
        for (int i = 0; i < 10; i++) {
            model.reset(LocalModelServer.textResponse("reply-" + i));
            var result =
                    context.getBean(AgentService.class)
                            .process(
                                    new ProcessRequest(
                                            "test", user, "answer", "message-" + i, "test"));
            assertThat(result.status()).isEqualTo(TurnStatus.SUCCEEDED);
            assertThat(result.persistenceStatus()).isEqualTo(PersistenceStatus.CONFIRMED);
            assertThat(model.calls()).isOne();
            var current =
                    jdbc.queryForObject(
                            "select session_id from agent_turns where turn_id=?",
                            java.util.UUID.class,
                            result.turnId());
            if (session == null) {
                session = current;
            }
            assertThat(current).isEqualTo(session);
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from llm_calls where turn_id=?",
                                    Integer.class,
                                    result.turnId()))
                    .isOne();
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from session_messages where turn_id=?",
                                    Integer.class,
                                    result.turnId()))
                    .isEqualTo(2);
            if (i > 0) {
                assertThat(model.requests().getFirst())
                        .contains("message-" + (i - 1), "reply-" + (i - 1));
            }
        }
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from agent_turns where session_id=? and status='SUCCEEDED'",
                                Integer.class,
                                session))
                .isEqualTo(10);
        assertThat(
                        jdbc.queryForObject(
                                "select lease_owner from sessions where session_id=?",
                                String.class,
                                session))
                .isNull();
    }

    @Test
    void slowTurnStorageStillReachesModelAndConfirmsResult(
            org.springframework.context.ApplicationContext context) {
        var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        // 每次 INSERT/UPDATE 延迟1.1秒：分别覆盖起步、续租和正常收尾，审计不注入延迟。
        jdbc.execute(
                """
                CREATE FUNCTION slow_turn_storage() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN PERFORM pg_sleep(1.1); RETURN NEW; END $$
                """);
        jdbc.execute(
                """
                CREATE TRIGGER slow_turn_storage BEFORE INSERT OR UPDATE ON agent_turns
                FOR EACH ROW EXECUTE FUNCTION slow_turn_storage()
                """);
        jdbc.execute(
                """
                CREATE TRIGGER slow_session_storage BEFORE UPDATE ON sessions
                FOR EACH ROW EXECUTE FUNCTION slow_turn_storage()
                """);
        model.reset();
        try {
            var result =
                    context.getBean(AgentService.class)
                            .process(
                                    new ProcessRequest(
                                            "test", "slow-storage-user", "answer", "你好", "test"));
            assertThat(result.status()).isEqualTo(TurnStatus.SUCCEEDED);
            assertThat(result.persistenceStatus()).isEqualTo(PersistenceStatus.CONFIRMED);
            assertThat(model.calls()).isOne();
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from session_messages where turn_id=? and type='USER'",
                                    Integer.class,
                                    result.turnId()))
                    .isOne();
            assertThat(
                            jdbc.queryForObject(
                                    "select status from agent_turns where turn_id=?",
                                    String.class,
                                    result.turnId()))
                    .isEqualTo("SUCCEEDED");
            assertThat(
                            jdbc.queryForObject(
                                    "select lease_owner from sessions where session_id=(select session_id from agent_turns where turn_id=?)",
                                    String.class,
                                    result.turnId()))
                    .isNull();
        } finally {
            jdbc.execute("DROP TRIGGER slow_turn_storage ON agent_turns");
            jdbc.execute("DROP TRIGGER slow_session_storage ON sessions");
            jdbc.execute("DROP FUNCTION slow_turn_storage()");
        }
    }

    @Test
    void sandboxDenialIsAuditedAndExplainedToSecondModelWithoutHttp(
            org.springframework.context.ApplicationContext context) {
        int before = weatherCalls.get();
        model.reset(
                LocalModelServer.toolResponse(
                        "denied-call",
                        "http://localhost:"
                                + weather.getAddress().getPort()
                                + "/weather?token=DENIED_SECRET"));
        var result =
                context.getBean(AgentService.class)
                        .process(
                                new ProcessRequest(
                                        "test", "denied-user", "weather", "查天气", "test"));
        assertThat(result.status()).isEqualTo(TurnStatus.SUCCEEDED);
        assertThat(model.calls()).isEqualTo(2);
        assertThat(weatherCalls).hasValue(before);
        var second =
                tools.jackson.databind.json.JsonMapper.builder()
                        .build()
                        .readTree(model.requests().get(1));
        // 未预先授权的域名需要用户确认；该入口无人值守，所以直接拒绝并告诉模型原因。
        assertThat(second.get("messages").get(3).get("content").asText())
                .contains("DENIED", "无人值守")
                .doesNotContain("DENIED_SECRET");
        var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        assertThat(
                        jdbc.queryForObject(
                                "select status from tool_invocations where turn_id=?",
                                String.class,
                                result.turnId()))
                .isEqualTo("sandbox_denied");
    }

    @Test
    void realManagerAssemblyHonorsMaxIterationsWithoutSecondModelCall(
            org.springframework.context.ApplicationContext context) {
        model.reset(
                LocalModelServer.toolResponse(
                        "bounded-call",
                        "http://127.0.0.1:" + weather.getAddress().getPort() + "/weather"));
        weatherCalls.set(0);
        var result =
                context.getBean(AgentService.class)
                        .process(
                                new ProcessRequest(
                                        "test", "bounded-user", "bounded", "执行", "test"));

        assertThat(result.status()).isEqualTo(TurnStatus.MAX_ITERATIONS);
        assertThat(result.iterations()).isEqualTo(1);
        assertThat(result.persistenceStatus()).isEqualTo(PersistenceStatus.CONFIRMED);
        assertThat(model.calls()).isOne();
        assertThat(weatherCalls).hasValue(1);
        var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from tool_invocations where turn_id=? and status='success'",
                                Integer.class,
                                result.turnId()))
                .isOne();
    }

    @Test
    void realProviderToolLoopStopsWhenSlowBodyConsumesSharedDeadline(
            org.springframework.context.ApplicationContext context) {
        model.reset(
                LocalModelServer.toolResponse(
                        "deadline-call",
                        "http://127.0.0.1:" + weather.getAddress().getPort() + "/weather"));
        weatherCalls.set(0);
        var releaseResponse = new java.util.concurrent.CountDownLatch(1);
        beforeHttp.set(() -> {
            try {
                releaseResponse.await(3, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        long started = System.nanoTime();
        try {
            var result =
                    context.getBean(AgentService.class)
                            .process(
                                    new ProcessRequest(
                                            "test", "deadline-user", "deadline", "执行", "test"));
            assertThat(result.status()).isEqualTo(TurnStatus.TIMED_OUT);
            // 超时由 AgentService 的异常收口路径返回，结果迭代数按契约为 0；
            // 已开始的模型/工具调用通过下面的调用计数和数据库审计确认。
            assertThat(result.iterations()).isZero();
            assertThat(result.persistenceStatus()).isEqualTo(PersistenceStatus.CONFIRMED);
            assertThat(model.calls()).isOne();
            assertThat(weatherCalls).hasValue(1);
            var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            assertThat(
                            jdbc.queryForObject(
                                    "select iterations from agent_turns where turn_id=?",
                                    Integer.class,
                                    result.turnId()))
                    .isZero();
            assertThat(
                            java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                                    System.nanoTime() - started))
                    .isLessThan(1200);
        } finally {
            releaseResponse.countDown();
            beforeHttp.set(() -> {});
        }
    }
}
