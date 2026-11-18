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
    @jakarta.annotation.Resource org.springframework.context.ApplicationContext application;

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
    void nestedCredentialsAreAbsentFromPersistedAndRestoredToolHistory() {
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        var scripted = json.readTree(response("nested-history", "/"));
        var function =
                (tools.jackson.databind.node.ObjectNode)
                        scripted.get("choices")
                                .get(0)
                                .get("message")
                                .get("tool_calls")
                                .get(0)
                                .get("function");
        function.put("name", "retry_tool");
        function.put(
                "arguments",
                "{\"nested\":{\"authorization\":\"Bearer NESTED_SECRET\",\"note\":\"local-test-key\"},\"url\":\"https://example.com/?token=QUERY_SECRET\"}");
        model.reset(json.writeValueAsString(scripted), LocalModelServer.textResponse("完成"));
        String user = UUID.randomUUID().toString();
        var result = service.process(new ProcessRequest("test", user, "normal", "执行", "test"));
        assertThat(result.status()).isEqualTo(TurnStatus.SUCCEEDED);
        assertThat(retryCalls).hasValue(2);
        var calls =
                jdbc.queryForObject(
                        "select tool_calls::text from session_messages where turn_id=? and jsonb_array_length(tool_calls)>0",
                        String.class,
                        result.turnId());
        assertThat(calls)
                .contains("nested-history", "nested", "***")
                .doesNotContain("NESTED_SECRET", "local-test-key", "QUERY_SECRET");
        assertThat(model.requests().getLast())
                .contains("nested-history", "nested")
                .doesNotContain("NESTED_SECRET", "local-test-key", "QUERY_SECRET");
        model.reset(LocalModelServer.textResponse("继续回答"));
        var resumed = service.process(new ProcessRequest("test", user, "normal", "继续", "test"));
        assertThat(resumed.status()).isEqualTo(TurnStatus.SUCCEEDED);
        assertThat(model.requests().getFirst())
                .contains("nested-history", "nested")
                .doesNotContain("NESTED_SECRET", "local-test-key", "QUERY_SECRET");
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
        ((tools.jackson.databind.node.ObjectNode) calls.get(2).get("function"))
                .put(
                        "arguments",
                        "{\"token\":\"SKIP_SECRET\",\"padding\":\"" + "x".repeat(12000) + "\"}");
        model.reset(
                json.writeValueAsString(first),
                response("four", "/"),
                LocalModelServer.textResponse("完成"));
        var events = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var result =
                service.process(
                        new ProcessRequest(
                                "test", UUID.randomUUID().toString(), "normal", "执行", "test"),
                        new com.agentos.core.turn.TurnSink() {
                            public void onToolCall(String id, String name, String arguments) {
                                if (id.equals("three")) {
                                    events.add(arguments);
                                }
                            }
                        });
        assertThat(result.status()).isEqualTo(TurnStatus.SUCCEEDED);
        assertThat(model.calls()).isEqualTo(3);
        assertThat(httpCalls).hasValue(2);
        var rows =
                jdbc.queryForList(
                        "select tool_call_id,status,attempts,input_json::text as input from tool_invocations where turn_id=? order by iteration,id",
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
        Object input = json.readValue(rows.get(2).get("input").toString(), Object.class);
        String summary = input instanceof String text ? text : json.writeValueAsString(input);
        assertThat(summary)
                .hasSizeLessThanOrEqualTo(10000)
                .contains("摘要截断")
                .doesNotContain("SKIP_SECRET");
        assertThat(events)
                .singleElement()
                .satisfies(arguments ->
                        assertThat(arguments)
                                .hasSizeLessThanOrEqualTo(10000)
                                .contains("摘要截断")
                                .doesNotContain("SKIP_SECRET"));
    }

    @Test
    void invalidJsonAnywhereRejectsBatchWithoutHttpAndLetsModelContinue() {
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        for (boolean invalidFirst : List.of(true, false)) {
            httpCalls.set(0);
            var first = json.readTree(response("good", "/"));
            var calls =
                    (tools.jackson.databind.node.ArrayNode)
                            first.get("choices").get(0).get("message").get("tool_calls");
            var bad =
                    json.readTree(response("bad", "/"))
                            .get("choices")
                            .get(0)
                            .get("message")
                            .get("tool_calls")
                            .get(0);
            ((tools.jackson.databind.node.ObjectNode) bad.get("function"))
                    .put("arguments", "{BROKEN_PRIVATE");
            if (invalidFirst) {
                calls.insert(0, bad);
            } else {
                calls.add(bad);
            }
            model.reset(
                    json.writeValueAsString(first), LocalModelServer.textResponse("参数有误，未执行工具"));
            var result = process("normal");
            assertThat(result.status()).isEqualTo(TurnStatus.SUCCEEDED);
            assertThat(model.calls()).isEqualTo(2);
            assertThat(httpCalls).hasValue(0);
            var rows =
                    jdbc.queryForList(
                            "select tool_call_id,status,attempts from tool_invocations where turn_id=? order by id",
                            result.turnId());
            assertThat(rows)
                    .hasSize(2)
                    .allSatisfy(row ->
                            assertThat(row)
                                    .containsEntry("status", "failed")
                                    .containsEntry("attempts", 0));
            assertThat(model.requests().getLast()).contains("good", "bad", "未执行");
        }
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
    void postResultLeaseOrDeadlineFailurePersistsBoundedOriginalAudit() {
        var turns = application.getBean(com.agentos.core.session.TurnStore.class);
        var budgets = application.getBean(TurnBudgetRegistry.class);
        for (boolean deadline : List.of(false, true)) {
            var budget = com.agentos.core.turn.TurnExecutionBudget.start(Duration.ofSeconds(10));
            var turn =
                    turns.startTurn(
                            UUID.randomUUID(),
                            new com.agentos.core.session.SessionKey(
                                    "test", UUID.randomUUID().toString(), "normal"),
                            "test",
                            "执行",
                            budget);
            budgets.register(turn.turnId(), budget);
            var returned = new java.util.concurrent.atomic.AtomicBoolean();
            var executions = new AtomicInteger();
            var originalId = new java.util.concurrent.atomic.AtomicLong();
            String raw = "token=POST_RESULT_SECRET\n" + "x".repeat(12000);
            ToolCallback delegate =
                    new ToolCallback() {
                        public ToolDefinition getToolDefinition() {
                            return ToolDefinition.builder()
                                    .name("test_tool")
                                    .description("测试")
                                    .inputSchema("{}")
                                    .build();
                        }

                        public String call(String input) {
                            executions.incrementAndGet();
                            originalId.set(
                                    jdbc.queryForObject(
                                            "select id from tool_invocations where turn_id=? and status='in_progress'",
                                            Long.class,
                                            turn.turnId()));
                            returned.set(true);
                            return raw;
                        }
                    };
            var ledger =
                    new com.agentos.core.turn.ToolCallLedger(
                            List.of(
                                    new org.springframework.ai.chat.messages.AssistantMessage
                                            .ToolCall(
                                            "post-result", "function", "test_tool", "{}")),
                            () -> {
                                if (returned.get()) {
                                    if (deadline) {
                                        throw new com.agentos.core.turn
                                                .TurnDeadlineExceededException();
                                    }
                                    throw new com.agentos.core.turn.LeaseLostException();
                                }
                            });
            var agent =
                    new com.agentos.core.agent.AgentLoader(workspace.resolve("agents"))
                            .load("normal");
            var toolContext =
                    new org.springframework.ai.chat.model.ToolContext(
                            java.util.Map.of(
                                    com.agentos.core.turn.ToolContextKeys.TURN_CONTEXT,
                                            new com.agentos.core.turn.TurnContext(
                                                    turn.sessionId(),
                                                    turn.turnId(),
                                                    agent,
                                                    "test",
                                                    1,
                                                    budget.deadline()),
                                    com.agentos.core.turn.ToolContextKeys.TURN_BUDGET, budget,
                                    com.agentos.core.turn.ToolContextKeys.TOOL_CALL_LEDGER,
                                            ledger));
            var governed =
                    new GovernedToolCallback(
                            delegate,
                            new ToolPolicy(true, Duration.ofSeconds(2), 10000),
                            application.getBean(ToolInvocationRecorder.class),
                            application.getBean(Redactor.class));
            try {
                org.assertj.core.api.Assertions.assertThatThrownBy(
                                () -> governed.call("{}", toolContext))
                        .isInstanceOf(
                                deadline
                                        ? com.agentos.core.turn.TurnDeadlineExceededException.class
                                        : com.agentos.core.turn.LeaseLostException.class);
                var row =
                        jdbc.queryForMap(
                                "select id,status,attempts,result_text,result_chars,result_truncated from tool_invocations where turn_id=?",
                                turn.turnId());
                assertThat(row)
                        .containsEntry("id", originalId.get())
                        .containsEntry("status", deadline ? "timeout" : "interrupted")
                        .containsEntry("attempts", 1)
                        .containsEntry("result_chars", raw.length())
                        .containsEntry("result_truncated", true);
                assertThat(row.get("result_text").toString())
                        .hasSizeLessThanOrEqualTo(10000)
                        .contains("结果已截断")
                        .doesNotContain("POST_RESULT_SECRET");
                assertThat(executions).hasValue(1);
                assertThat(model.calls()).isZero();
                assertThat(httpCalls).hasValue(0);
            } finally {
                turns.finishAndRelease(turn, "INTERRUPTED", "测试结束", "测试结束");
                budgets.remove(turn.turnId());
            }
        }
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
