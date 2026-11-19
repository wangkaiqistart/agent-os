package com.agentos.boot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.boot.support.LocalModelServer;
import com.agentos.core.agent.AgentProfile;
import com.agentos.core.audit.ToolInvocationRecorder;
import com.agentos.core.tool.Redactor;
import com.agentos.core.tool.ToolCatalog;
import com.agentos.core.tool.ToolCatalogSnapshot;
import com.agentos.core.tool.ToolDescriptor;
import com.agentos.core.tool.ToolPolicy;
import com.agentos.core.turn.AgentService;
import com.agentos.core.turn.ProcessRequest;
import com.agentos.core.turn.TurnStatus;
import com.agentos.tool.govern.GovernedToolCallback;
import com.agentos.tool.http.HttpTools;
import com.agentos.tool.registry.ToolRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

/** 真实Manager与PostgreSQL验证建轮次前解析一次，拒绝路径仍关联原调用来源。 */
@Tag("db")
@ExtendWith(BootDatabaseTestEnvironment.class)
@Testcontainers
@SpringBootTest(
        classes = {
            AgentOSApplication.class,
            ToolSnapshotBeforeTurnIntegrationTest.Configuration.class
        },
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.main.web-application-type=none",
            "spring.config.import=",
            "spring.ai.tools.limits.max-total-tool-calls=1"
        })
class ToolSnapshotBeforeTurnIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    DockerImageName.parse("pgvector/pgvector:pg17")
                            .asCompatibleSubstituteFor("postgres"));

    private static Path workspace;
    private static LocalModelServer model;
    private static com.sun.net.httpserver.HttpServer http;
    private static final AtomicInteger actions = new AtomicInteger();
    private static final AtomicInteger resolves = new AtomicInteger();
    private static final AtomicInteger legacyReads = new AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicLong turnsAtResolve =
            new java.util.concurrent.atomic.AtomicLong();

    @BeforeAll
    static void prepare() throws Exception {
        workspace = Files.createTempDirectory("tool-snapshot-");
        for (String name : List.of("allowed", "denied", "unknown")) {
            var directory = Files.createDirectories(workspace.resolve("agents").resolve(name));
            String tools =
                    name.equals("denied")
                            ? "[]"
                            : name.equals("unknown") ? "[missing]" : "[http_get]";
            Files.writeString(
                    directory.resolve("AGENT.md"),
                    "---\nname: "
                            + name
                            + "\nprovider:\n  model: test-model\ntools: "
                            + tools
                            + "\n---\n测试。\n");
        }
        model = LocalModelServer.start();
        http =
                com.sun.net.httpserver.HttpServer.create(
                        new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext(
                "/",
                exchange -> {
                    actions.incrementAndGet();
                    try (exchange) {
                        byte[] body =
                                "fixture-result".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, body.length);
                        exchange.getResponseBody().write(body);
                    }
                });
        http.start();
    }

    @AfterAll
    static void cleanup() {
        if (model != null) {
            model.close();
        }
        if (http != null) {
            http.stop(0);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("agentos.workspace.root", () -> workspace.toString());
        registry.add("agentos.providers.primary.base-url", () -> model.baseUrl() + "/v1");
        registry.add("agentos.providers.primary.api-key", () -> "fixture-key");
        registry.add("agentos.embedding.enabled", () -> false);
        registry.add("agentos.tools.http.allowed-domains", () -> "127.0.0.1");
    }

    @Test
    void snapshotBoundBeforeTurnAndUsedAcrossAllIterations(
            org.springframework.context.ApplicationContext context) {
        var service = context.getBean(AgentService.class);
        var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        for (String mode : List.of("allowed", "denied", "invalid", "limited")) {
            actions.set(0);
            resolves.set(0);
            legacyReads.set(0);
            String args =
                    mode.equals("invalid")
                            ? "broken"
                            : JsonMapper.builder()
                                    .build()
                                    .writeValueAsString(
                                            Map.of(
                                                    "url",
                                                    "http://127.0.0.1:"
                                                            + http.getAddress().getPort()
                                                            + "/"));
            model.reset(
                    response(mode, args, mode.equals("limited")),
                    LocalModelServer.textResponse("完成"));
            long turnsBefore = jdbc.queryForObject("select count(*) from agent_turns", Long.class);
            var result =
                    service.process(
                            new ProcessRequest(
                                    "test",
                                    java.util.UUID.randomUUID().toString(),
                                    mode.equals("denied") ? "denied" : "allowed",
                                    "测试",
                                    "test"));
            assertThat(result.status()).isEqualTo(TurnStatus.SUCCEEDED);
            assertThat(resolves).hasValue(1);
            assertThat(turnsAtResolve).hasValue(turnsBefore);
            assertThat(legacyReads).hasValue(0);
            assertThat(actions).hasValue(mode.equals("denied") || mode.equals("invalid") ? 0 : 1);
            var rows =
                    jdbc.queryForList(
                            "select tool_call_id,tool_source,status from tool_invocations where turn_id=? order by tool_call_id",
                            result.turnId());
            assertThat(rows).hasSize(mode.equals("limited") ? 2 : 1);
            assertThat(rows)
                    .allSatisfy(
                            row -> assertThat(row.get("tool_source")).isEqualTo("java:fixture"));
            assertThat(rows.getFirst().get("tool_call_id")).isEqualTo(mode);
            assertThat(rows.getFirst().get("status"))
                    .isEqualTo(
                            mode.equals("denied") || mode.equals("invalid") ? "failed" : "success");
            if (mode.equals("limited")) {
                assertThat(rows.getLast().get("status")).isEqualTo("limit_skipped");
            }
            assertThat(model.requests().getLast()).contains(mode);
            if (mode.equals("denied")) {
                assertThat(model.requests().getFirst()).doesNotContain("\"tools\"");
            }
        }
        long before = jdbc.queryForObject("select count(*) from agent_turns", Long.class);
        model.reset();
        assertThatThrownBy(() ->
                        service.process(
                                new ProcessRequest("test", "unknown", "unknown", "测试", "test")))
                .isInstanceOf(com.agentos.core.tool.UnknownAgentToolException.class);
        assertThat(model.calls()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from agent_turns", Long.class))
                .isEqualTo(before);
    }

    private String response(String id, String args, boolean twice) {
        var call =
                Map.of(
                        "id",
                        id,
                        "type",
                        "function",
                        "function",
                        Map.of("name", "http_get", "arguments", args));
        var calls =
                twice
                        ? List.of(
                                call,
                                Map.of(
                                        "id",
                                        id + "-skipped",
                                        "type",
                                        "function",
                                        "function",
                                        Map.of("name", "http_get", "arguments", args)))
                        : List.of(call);
        return JsonMapper.builder()
                .build()
                .writeValueAsString(
                        Map.of(
                                "id",
                                "fixture",
                                "object",
                                "chat.completion",
                                "model",
                                "test-model",
                                "choices",
                                List.of(
                                        Map.of(
                                                "index",
                                                0,
                                                "message",
                                                Map.of(
                                                        "role",
                                                        "assistant",
                                                        "content",
                                                        "",
                                                        "tool_calls",
                                                        calls),
                                                "finish_reason",
                                                "tool_calls"))));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean
        @Primary
        ToolCatalog snapshotCatalog(
                HttpTools httpTools,
                ToolInvocationRecorder recorder,
                Redactor redactor,
                DataSource dataSource) {
            var callback =
                    java.util.Arrays.stream(ToolCallbacks.from(httpTools))
                            .filter(cb -> cb.getToolDefinition().name().equals("http_get"))
                            .findFirst()
                            .orElseThrow();
            var raw =
                    new ToolDescriptor(
                            "http_get",
                            callback.getToolDefinition().description(),
                            callback.getToolDefinition().inputSchema(),
                            "java:fixture",
                            null,
                            new ToolPolicy(true, Duration.ofSeconds(3), 10000),
                            callback.getToolMetadata(),
                            callback,
                            callback,
                            ToolDescriptor.Availability.AVAILABLE,
                            1);
            var governed = new GovernedToolCallback(raw, recorder, redactor, 10, 30);
            var descriptor =
                    new ToolDescriptor(
                            raw.name(),
                            raw.description(),
                            raw.inputSchema(),
                            raw.source(),
                            null,
                            raw.policy(),
                            raw.metadata(),
                            governed,
                            callback,
                            raw.availability(),
                            1);
            var delegate = ToolRegistry.fromDescriptors(List.of(descriptor));
            return new ToolCatalog() {
                public void validate(AgentProfile profile) {
                    legacyReads.incrementAndGet();
                    delegate.validate(profile);
                }

                public List<ToolCallback> callbacks(AgentProfile profile) {
                    legacyReads.incrementAndGet();
                    return delegate.callbacks(profile);
                }

                public ToolCatalogSnapshot resolve(AgentProfile profile) {
                    resolves.incrementAndGet();
                    turnsAtResolve.set(
                            new JdbcTemplate(dataSource)
                                    .queryForObject(
                                            "select count(*) from agent_turns", Long.class));
                    return delegate.resolve(profile);
                }
            };
        }
    }
}
