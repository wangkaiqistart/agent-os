package com.agentos.boot;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentos.boot.support.LocalModelServer;
import com.agentos.core.audit.ToolInvocationRecorder;
import com.agentos.core.config.AuditProperties;
import com.agentos.core.tool.Redactor;
import com.agentos.core.tool.ToolCatalog;
import com.agentos.core.tool.ToolPolicy;
import com.agentos.core.tool.TransientToolException;
import com.agentos.core.turn.AgentService;
import com.agentos.core.turn.PersistenceStatus;
import com.agentos.core.turn.ProcessRequest;
import com.agentos.core.turn.TurnBudgetRegistry;
import com.agentos.core.turn.TurnStatus;
import com.agentos.storage.audit.AuditTransactions;
import com.agentos.storage.audit.JpaToolInvocationRecorder;
import com.agentos.storage.session.ToolInvocationEntity;
import com.agentos.storage.session.ToolInvocationRepository;
import com.agentos.tool.govern.GovernedToolCallback;
import com.agentos.tool.http.HttpTools;
import com.agentos.tool.registry.ToolRegistry;
import com.sun.net.httpserver.HttpServer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingProperties;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** 使用真实官方 Manager、治理回调、PostgreSQL、本地模型和 HTTP 联合验证次数与故障。 */
@Tag("db")
@ExtendWith(BootDatabaseTestEnvironment.class)
@Testcontainers
@SpringBootTest(
        classes = AgentOSApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.config.import=",
            "spring.ai.tools.limits.max-calls-per-tool-default=2",
            "spring.ai.tools.limits.max-total-tool-calls=3"
        })
@Import(ReActDeadlineIntegrationTest.AcceptanceConfiguration.class)
class ReActDeadlineIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    DockerImageName.parse("pgvector/pgvector:pg17")
                            .asCompatibleSubstituteFor("postgres"));

    static Path workspace;
    static LocalModelServer model;
    static HttpServer http;
    static java.util.concurrent.ExecutorService httpExecutor;
    static final AtomicInteger httpCalls = new AtomicInteger();
    static final AtomicInteger retryCalls = new AtomicInteger();

    @jakarta.annotation.Resource AgentService service;
    @jakarta.annotation.Resource JdbcTemplate jdbc;

    @BeforeAll
    static void start() throws Exception {
        workspace = Files.createTempDirectory("agentos-tool-acceptance");
        for (String agent : List.of("normal", "deadline")) {
            Path dir = Files.createDirectories(workspace.resolve("agents/" + agent));
            Files.writeString(
                    dir.resolve("AGENT.md"),
                    "---\nname: "
                            + agent
                            + "\nprovider:\n  model: test-model\ntools: [http_get, retry_tool]\nsettings:\n  turn_timeout: "
                            + (agent.equals("deadline") ? "1s" : "10s")
                            + "\n---\n调用工具。\n");
        }
        model = LocalModelServer.start();
        http = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        httpExecutor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        http.setExecutor(httpExecutor);
        http.createContext(
                "/",
                e -> {
                    try (e) {
                        httpCalls.incrementAndGet();
                        byte[] body =
                                (e.getRequestURI().getPath().equals("/large")
                                                ? "BODY_PRIVATE" + "x".repeat(1048576)
                                                : "ok token=BODY_PRIVATE")
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        e.sendResponseHeaders(200, body.length);
                        if (e.getRequestURI().getPath().equals("/slow")) {
                            try {
                                Thread.sleep(1500);
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                            }
                        }
                        e.getResponseBody().write(body);
                    }
                });
        http.start();
    }

    @AfterAll
    static void stop() {
        if (model != null) {
            model.close();
        }
        if (http != null) {
            http.stop(0);
        }
        if (httpExecutor != null) {
            httpExecutor.shutdownNow();
        }
    }

    @BeforeEach
    void reset() {
        httpCalls.set(0);
        retryCalls.set(0);
        model.reset();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("agentos.workspace.root", () -> workspace.toString());
        registry.add("agentos.providers.primary.base-url", () -> model.baseUrl() + "/v1");
        registry.add("agentos.providers.primary.api-key", () -> "local-test-key");
        registry.add("agentos.embedding.enabled", () -> false);
        registry.add("agentos.tools.http.allowed-domains", () -> "127.0.0.1");
    }

    static String response(String id, String path) {
        return LocalModelServer.toolResponse(
                id, "http://127.0.0.1:" + http.getAddress().getPort() + path);
    }

    private com.agentos.core.turn.TurnResult process(String agent) {
        return service.process(
                new ProcessRequest("test", UUID.randomUUID().toString(), agent, "执行", "test"));
    }

    @Test
    void retryAndNextIterationShareActualAttemptBudget() {
        model.reset(
                response("retry", "/").replace("\"name\":\"http_get\"", "\"name\":\"retry_tool\""),
                response("allowed", "/"),
                response("skipped", "/"),
                LocalModelServer.textResponse("完成"));
        var result = process("normal");
        assertThat(result.status()).isEqualTo(TurnStatus.SUCCEEDED);
        assertThat(result.persistenceStatus()).isEqualTo(PersistenceStatus.CONFIRMED);
        assertThat(model.calls()).isEqualTo(4);
        assertThat(retryCalls).hasValue(2);
        assertThat(httpCalls).hasValue(1);
        var rows =
                jdbc.queryForList(
                        "select tool_call_id,status,attempts from tool_invocations where turn_id=? order by iteration",
                        result.turnId());
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0)).containsEntry("status", "success").containsEntry("attempts", 2);
        assertThat(rows.get(1)).containsEntry("status", "success").containsEntry("attempts", 1);
        assertThat(rows.get(2))
                .containsEntry("tool_call_id", "skipped")
                .containsEntry("status", "limit_skipped")
                .containsEntry("attempts", 0);
        assertThat(model.requests().getLast())
                .contains("skipped", "上限")
                .doesNotContain("BODY_PRIVATE");
    }

    @Test
    void sameResponseAndLaterIterationRespectOfficialPerToolLimit() {
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        var first = json.readTree(response("one", "/"));
        var calls =
                (tools.jackson.databind.node.ArrayNode)
                        first.get("choices").get(0).get("message").get("tool_calls");
        calls.add(
                json.readTree(response("two", "/"))
                        .get("choices")
                        .get(0)
                        .get("message")
                        .get("tool_calls")
                        .get(0));
        calls.add(
                json.readTree(response("three", "/"))
                        .get("choices")
                        .get(0)
                        .get("message")
                        .get("tool_calls")
                        .get(0));
        model.reset(
                json.writeValueAsString(first),
                response("four", "/"),
                LocalModelServer.textResponse("完成"));
        var result = process("normal");
        assertThat(result.status()).isEqualTo(TurnStatus.SUCCEEDED);
        assertThat(model.calls()).isEqualTo(3);
        assertThat(httpCalls).hasValue(2);
        var rows =
                jdbc.queryForList(
                        "select tool_call_id,status,attempts from tool_invocations where turn_id=? order by iteration,id",
                        result.turnId());
        assertThat(rows).hasSize(4);
        assertThat(rows.subList(2, 4))
                .extracting(r -> r.get("tool_call_id"))
                .containsExactly("three", "four");
        assertThat(rows.subList(2, 4))
                .allSatisfy(r ->
                        assertThat(r)
                                .containsEntry("status", "limit_skipped")
                                .containsEntry("attempts", 0));
    }

    @Test
    void slowBodyStopsTurnWithoutNextModelOrHttpRetry() {
        model.reset(response("slow", "/slow"));
        long start = System.nanoTime();
        var result = process("deadline");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
        assertThat(result.status()).isEqualTo(TurnStatus.TIMED_OUT);
        assertThat(model.calls()).isOne();
        assertThat(httpCalls).hasValue(1);
        assertThat(
                        jdbc.queryForMap(
                                "select status,attempts from tool_invocations where turn_id=?",
                                result.turnId()))
                .containsEntry("status", "timeout")
                .containsEntry("attempts", 1);
    }

    @Test
    void oversizedBodyReturnsSafeToolErrorAndContinuesWithOneHttpRequest() {
        model.reset(response("large", "/large"), LocalModelServer.textResponse("已说明超限"));
        var result = process("normal");
        assertThat(result.status()).isEqualTo(TurnStatus.SUCCEEDED);
        assertThat(httpCalls).hasValue(1);
        assertThat(model.calls()).isEqualTo(2);
        assertThat(model.requests().getLast()).contains("正文超过大小上限").doesNotContain("BODY_PRIVATE");
        assertThat(
                        jdbc.queryForMap(
                                "select status,attempts,result_text,error_message from tool_invocations where turn_id=?",
                                result.turnId()))
                .containsEntry("status", "failed")
                .containsEntry("attempts", 1)
                .containsEntry("result_text", null);
    }

    @Test
    void completionFailurePersistsUnconfirmedAndNeverReplaysSideEffect() {
        model.reset(response("audit-failure", "/"));
        var result = process("normal");
        assertThat(result.status()).isEqualTo(TurnStatus.FAILED);
        assertThat(result.persistenceStatus()).isEqualTo(PersistenceStatus.UNCONFIRMED);
        assertThat(result.text()).contains("可能已经执行");
        assertThat(model.calls()).isOne();
        assertThat(httpCalls).hasValue(1);
        assertThat(
                        jdbc.queryForMap(
                                "select status,attempts,result_text from tool_invocations where turn_id=?",
                                result.turnId()))
                .containsEntry("status", "unconfirmed")
                .containsEntry("attempts", 1)
                .containsEntry("result_text", null);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class AcceptanceConfiguration {
        @Bean
        ToolInvocationRecorder acceptanceRecorder(
                ToolInvocationRepository repository,
                Redactor redactor,
                AuditTransactions transactions,
                TurnBudgetRegistry budgets,
                AuditProperties properties) {
            var wrapper =
                    org.mockito.Mockito.mock(
                            ToolInvocationRepository.class,
                            org.mockito.AdditionalAnswers.delegatesTo(repository));
            org.mockito.Mockito.doAnswer(
                            i -> {
                                ToolInvocationEntity row = i.getArgument(0);
                                if ("audit-failure".equals(row.getToolCallId())
                                        && "success".equals(row.getStatus())) {
                                    throw new org.springframework.dao
                                            .DataAccessResourceFailureException("模拟完成写失败");
                                }
                                return repository.saveAndFlush(row);
                            })
                    .when(wrapper)
                    .saveAndFlush(org.mockito.ArgumentMatchers.any());
            return new JpaToolInvocationRecorder(
                    wrapper, redactor, transactions, budgets, properties);
        }

        @Bean
        @Primary
        ToolCatalog acceptanceCatalog(
                HttpTools http,
                ToolInvocationRecorder recorder,
                Redactor redactor,
                ToolCallingProperties properties) {
            ToolCallback retry =
                    new ToolCallback() {
                        public ToolDefinition getToolDefinition() {
                            return ToolDefinition.builder()
                                    .name("retry_tool")
                                    .description("测试内部扩展重试")
                                    .inputSchema("{\"type\":\"object\"}")
                                    .build();
                        }

                        public String call(String input) {
                            if (retryCalls.incrementAndGet() == 1) {
                                throw new TransientToolException(false);
                            }
                            return "ok";
                        }
                    };
            return new ToolRegistry(
                    List.of(ToolCallbacks.from(http)[0], retry).stream()
                            .map(cb ->
                                    (ToolCallback)
                                            new GovernedToolCallback(
                                                    cb,
                                                    new ToolPolicy(
                                                            true, Duration.ofSeconds(5), 10000),
                                                    recorder,
                                                    redactor,
                                                    properties
                                                            .getLimits()
                                                            .getMaxCallsPerToolDefault(),
                                                    properties.getLimits().getMaxTotalToolCalls()))
                            .toList());
        }
    }
}
