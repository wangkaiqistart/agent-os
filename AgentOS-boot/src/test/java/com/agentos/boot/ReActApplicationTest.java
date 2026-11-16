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
        assertThat(context.getBeansOfType(LlmCallRecorder.class)).hasSize(1);
        assertThat(context.getBeansOfType(org.springframework.ai.chat.model.ChatModel.class))
                .isEmpty();
        assertThat(context.getBeansOfType(org.springframework.ai.embedding.EmbeddingModel.class))
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
        beforeHttp.set(() ->
                assertThat(
                                jdbc.queryForObject(
                                        "select count(*) from tool_invocations where tool_call_id=? and status='in_progress' and attempts=0",
                                        Integer.class,
                                        id))
                        .isOne());
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
                .doesNotContain("RESPONSE_SECRET", "local-test-key");
        assertThat(output.getAll())
                .doesNotContain("URL_SECRET", "RESPONSE_SECRET", "local-test-key");
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
        assertThat(second.get("messages").get(3).get("content").asText())
                .contains("沙箱拒绝")
                .doesNotContain("DENIED_SECRET");
        var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        assertThat(
                        jdbc.queryForObject(
                                "select status from tool_invocations where turn_id=?",
                                String.class,
                                result.turnId()))
                .isEqualTo("sandbox_denied");
    }
}
