package com.agentos.boot;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentos.boot.support.LocalModelServer;
import com.agentos.core.audit.AuditWriteException;
import com.agentos.core.audit.ToolInvocationRecord;
import com.agentos.core.audit.ToolInvocationRecorder;
import com.agentos.core.config.AuditProperties;
import com.agentos.core.tool.Redactor;
import com.agentos.core.turn.AgentService;
import com.agentos.core.turn.PersistenceStatus;
import com.agentos.core.turn.ProcessRequest;
import com.agentos.core.turn.TurnBudgetRegistry;
import com.agentos.core.turn.TurnResult;
import com.agentos.core.turn.TurnSink;
import com.agentos.core.turn.TurnStatus;
import com.agentos.storage.audit.AuditTransactions;
import com.agentos.storage.audit.JpaToolInvocationRecorder;
import com.agentos.storage.session.ToolInvocationRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

/** POST通过真实Manager和PostgreSQL，核对实际提交次数及原调用回填。 */
@Tag("db")
@ExtendWith(BootDatabaseTestEnvironment.class)
@Testcontainers
@SpringBootTest(
        classes = {AgentOSApplication.class, HttpPostAcceptanceIntegrationTest.Configuration.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.main.web-application-type=none",
            "spring.config.import=",
            "spring.ai.tools.limits.max-calls-per-tool-default=2"
        })
class HttpPostAcceptanceIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    DockerImageName.parse("pgvector/pgvector:pg17")
                            .asCompatibleSubstituteFor("postgres"));

    private static Path workspace;
    private static LocalModelServer model;
    private static com.sun.net.httpserver.HttpServer receiver;
    private static final java.util.concurrent.atomic.AtomicInteger received =
            new java.util.concurrent.atomic.AtomicInteger();
    private static String url;
    private static final java.util.concurrent.atomic.AtomicReference<ToolInvocationRecord>
            terminalCall = new java.util.concurrent.atomic.AtomicReference<>();
    private static final java.util.concurrent.atomic.AtomicReference<Runnable> afterBody =
            new java.util.concurrent.atomic.AtomicReference<>();
    private static final java.util.concurrent.atomic.AtomicInteger terminalReceived =
            new java.util.concurrent.atomic.AtomicInteger();
    private static volatile java.util.concurrent.CountDownLatch releaseResponse;
    private static volatile java.util.concurrent.CountDownLatch receiverFinished;
    private static final java.util.concurrent.atomic.AtomicReference<Throwable> receiverFailure =
            new java.util.concurrent.atomic.AtomicReference<>();

    @BeforeAll
    static void prepare() throws Exception {
        workspace = Files.createTempDirectory("post-acceptance-");
        for (String name : List.of("allowed", "denied", "terminal")) {
            Path agent = Files.createDirectories(workspace.resolve("agents/" + name));
            Files.writeString(
                    agent.resolve("AGENT.md"),
                    "---\nname: "
                            + name
                            + "\nprovider:\n  model: test-model\ntools: "
                            + (name.equals("denied") ? "[]" : "[http_post]")
                            + (name.equals("terminal") ? "\nsettings:\n  turn_timeout: 2s" : "")
                            + "\n---\n提交测试。\n");
        }
        receiver =
                com.sun.net.httpserver.HttpServer.create(
                        new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext(
                "/post",
                exchange -> {
                    received.incrementAndGet();
                    exchange.getRequestBody().readAllBytes();
                    byte[] bytes =
                            "{\"success\":false,\"detail\":\"plugin-fixture-key\"}"
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        receiver.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        receiver.createContext(
                "/terminal",
                exchange -> {
                    try {
                        assertThat(
                                        new String(
                                                exchange.getRequestBody().readAllBytes(),
                                                java.nio.charset.StandardCharsets.UTF_8))
                                .isEqualTo("提交正文");
                        terminalReceived.incrementAndGet();
                        afterBody.get().run();
                        releaseResponse.await(10, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (Throwable failure) {
                        receiverFailure.set(failure);
                    } finally {
                        exchange.close();
                        receiverFinished.countDown();
                    }
                });
        receiver.start();
        url = "http://127.0.0.1:" + receiver.getAddress().getPort() + "/post";
        model = LocalModelServer.start();
    }

    @AfterAll
    static void stop() {
        if (model != null) {
            model.close();
        }
        if (receiver != null) {
            receiver.stop(0);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("agentos.workspace.root", () -> workspace.toString());
        registry.add("agentos.providers.primary.base-url", () -> model.baseUrl() + "/v1");
        registry.add("agentos.providers.primary.api-key", () -> "plugin-fixture-key");
        registry.add("agentos.embedding.enabled", () -> false);
        registry.add("agentos.tools.http.allowed-domains[0]", () -> "127.0.0.1");
    }

    @Test
    void postUsesOriginalCallAndOneSubmissionDespiteUnknownCompletion(
            org.springframework.context.ApplicationContext context) {
        var service = context.getBean(AgentService.class);
        var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        for (String mode :
                List.of(
                        "allowed",
                        "denied",
                        "invalid",
                        "begin-failure",
                        "complete-failure",
                        "cancel-before-action",
                        "lease-before-action")) {
            int before = received.get();
            String args =
                    JsonMapper.builder()
                            .build()
                            .writeValueAsString(
                                    Map.of(
                                            "url",
                                            url,
                                            "body",
                                            "提交正文",
                                            "content_type",
                                            "text/plain"));
            model.reset(
                    response(mode, "http_post", mode.equals("invalid") ? "{}" : args),
                    LocalModelServer.textResponse("完成"));
            var result =
                    service.process(
                            new ProcessRequest(
                                    "test",
                                    java.util.UUID.randomUUID().toString(),
                                    mode.equals("denied") ? "denied" : "allowed",
                                    "提交测试",
                                    "test"));
            assertThat(received.get() - before)
                    .as(mode)
                    .isEqualTo(mode.equals("allowed") || mode.equals("complete-failure") ? 1 : 0);
            assertThat(result.status())
                    .as(mode)
                    .isEqualTo(
                            mode.endsWith("failure")
                                    ? TurnStatus.FAILED
                                    : mode.endsWith("before-action")
                                            ? TurnStatus.INTERRUPTED
                                            : TurnStatus.SUCCEEDED);
            var rows =
                    jdbc.queryForList(
                            "select tool_call_id,tool_source,status,attempts,idempotent from tool_invocations where turn_id=?",
                            result.turnId());
            if (mode.equals("begin-failure")) {
                assertThat(rows).isEmpty();
            } else {
                assertThat(rows).hasSize(1);
                assertThat(rows.getFirst())
                        .containsEntry("tool_call_id", mode)
                        .containsEntry("tool_source", "builtin")
                        .containsEntry(
                                "attempts",
                                mode.equals("allowed") || mode.equals("complete-failure") ? 1 : 0)
                        .containsEntry("idempotent", false);
            }
            if (!mode.endsWith("failure") && !mode.endsWith("before-action")) {
                assertThat(model.calls()).isEqualTo(2);
                assertThat(model.requests().getLast()).contains(mode);
            }
            if (mode.equals("complete-failure")) {
                assertThat(result.text()).contains("提交可能已生效");
                assertThat(result.persistenceStatus()).isEqualTo(PersistenceStatus.UNCONFIRMED);
                assertThat(
                                jdbc.queryForObject(
                                        "select final_text from agent_turns where turn_id=?",
                                        String.class,
                                        result.turnId()))
                        .contains("提交可能已生效");
            } else {
                assertThat(result.text()).as(mode).doesNotContain("提交可能已生效");
            }
            if (mode.equals("allowed")) {
                assertThat(model.requests().getLast())
                        .contains("CONFIRMED", "false")
                        .doesNotContain("plugin-fixture-key");
            }
        }
    }

    @Test
    void receivedPostRetainsSubmissionWarningAcrossFatalTermination(
            org.springframework.context.ApplicationContext context) throws Exception {
        var service = context.getBean(AgentService.class);
        var budgets = context.getBean(TurnBudgetRegistry.class);
        var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        var samples = new java.util.ArrayList<Map<String, Object>>();
        for (String mode : List.of("deadline", "cancel", "lease")) {
            int before = received.get();
            int terminalBefore = terminalReceived.get();
            terminalCall.set(null);
            receiverFailure.set(null);
            releaseResponse = new java.util.concurrent.CountDownLatch(1);
            receiverFinished = new java.util.concurrent.CountDownLatch(1);
            afterBody.set(() -> {
                var call = terminalCall.get();
                assertThat(call).isNotNull();
                if (mode.equals("cancel")) {
                    budgets.get(call.turnId()).cancel();
                } else if (mode.equals("lease")) {
                    jdbc.update(
                            "update sessions set lease_owner='terminal-external-fixture' where session_id=?",
                            call.sessionId());
                }
            });
            String args =
                    JsonMapper.builder()
                            .build()
                            .writeValueAsString(
                                    Map.of(
                                            "url",
                                            url.replace("/post", "/terminal"),
                                            "body",
                                            "提交正文",
                                            "content_type",
                                            "text/plain"));
            String laterArgs =
                    JsonMapper.builder()
                            .build()
                            .writeValueAsString(
                                    Map.of(
                                            "url",
                                            url,
                                            "body",
                                            "后续动作",
                                            "content_type",
                                            "text/plain"));
            model.reset(
                    response(
                            List.of(
                                    toolCall("terminal-" + mode, "http_post", args),
                                    toolCall("later-" + mode, "http_post", laterArgs))),
                    LocalModelServer.textResponse("不应进入下一次模型调用"));
            var error = new java.util.concurrent.atomic.AtomicReference<TurnResult>();
            try {
                var result =
                        service.process(
                                new ProcessRequest(
                                        "test",
                                        java.util.UUID.randomUUID().toString(),
                                        "terminal",
                                        "提交测试",
                                        "test"),
                                new TurnSink() {
                                    public void onError(TurnResult value) {
                                        error.set(value);
                                    }
                                });
                assertThat(receiverFailure.get()).isNull();
                assertThat(terminalReceived.get() - terminalBefore).as(mode).isEqualTo(1);
                assertThat(received.get() - before).as("同批后续动作 " + mode).isZero();
                assertThat(model.calls()).as(mode).isEqualTo(1);
                assertThat(result.status())
                        .isEqualTo(
                                mode.equals("deadline")
                                        ? TurnStatus.TIMED_OUT
                                        : TurnStatus.INTERRUPTED);
                assertThat(result.text()).as(mode).contains("提交可能已生效");
                assertThat(error.get()).isEqualTo(result);
                assertThat(result.persistenceStatus()).isEqualTo(PersistenceStatus.CONFIRMED);
                assertThat(
                                jdbc.queryForObject(
                                        "select final_text from agent_turns where turn_id=?",
                                        String.class,
                                        result.turnId()))
                        .contains("提交可能已生效");
                assertThat(
                                jdbc.queryForList(
                                        "select tool_call_id,tool_source,attempts from tool_invocations where turn_id=?",
                                        result.turnId()))
                        .singleElement()
                        .satisfies(row ->
                                assertThat(row)
                                        .containsEntry("tool_call_id", "terminal-" + mode)
                                        .containsEntry("tool_source", "builtin")
                                        .containsEntry("attempts", 1));
                samples.add(
                        Map.of(
                                "signal",
                                mode,
                                "status",
                                result.status().name(),
                                "persistenceStatus",
                                result.persistenceStatus().name(),
                                "requests",
                                terminalReceived.get() - terminalBefore,
                                "laterRequests",
                                received.get() - before,
                                "modelCalls",
                                model.calls(),
                                "resultWarning",
                                result.text().contains("提交可能已生效"),
                                "sinkWarning",
                                error.get().text().contains("提交可能已生效"),
                                "persistedWarning",
                                true,
                                "attempts",
                                1));
                if (mode.equals("lease")) {
                    assertThat(
                                    jdbc.queryForObject(
                                            "select lease_owner from sessions where session_id=?",
                                            String.class,
                                            terminalCall.get().sessionId()))
                            .isEqualTo("terminal-external-fixture");
                }
            } finally {
                releaseResponse.countDown();
                assertThat(receiverFinished.await(2, java.util.concurrent.TimeUnit.SECONDS))
                        .isTrue();
            }
        }
        Files.writeString(
                Path.of("target/tool-post-terminal-samples.json"),
                JsonMapper.builder().build().writeValueAsString(samples));
    }

    private Map<String, Object> toolCall(String id, String tool, String arguments) {
        return Map.of(
                "id",
                id,
                "type",
                "function",
                "function",
                Map.of("name", tool, "arguments", arguments));
    }

    private String response(String id, String tool, String arguments) {
        return response(List.of(toolCall(id, tool, arguments)));
    }

    private String response(List<Map<String, Object>> calls) {
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
        ToolInvocationRecorder postRecorder(
                ToolInvocationRepository repository,
                Redactor redactor,
                AuditTransactions transactions,
                TurnBudgetRegistry budgets,
                AuditProperties properties,
                org.springframework.jdbc.core.JdbcTemplate jdbc) {
            var delegate =
                    new JpaToolInvocationRecorder(
                            repository, redactor, transactions, budgets, properties);
            return new ToolInvocationRecorder() {
                public ToolInvocationRecord begin(ToolInvocationRecord record) {
                    if (record.toolCallId().equals("begin-failure")) {
                        throw new AuditWriteException("测试begin未确认", false);
                    }
                    var persisted = delegate.begin(record);
                    if (record.toolCallId().startsWith("terminal-")) {
                        terminalCall.set(record);
                    }
                    if (record.toolCallId().equals("cancel-before-action")) {
                        budgets.get(record.turnId()).cancel();
                    }
                    if (record.toolCallId().equals("lease-before-action")) {
                        jdbc.update(
                                "update sessions set lease_owner='external-fixture' where session_id=?",
                                record.sessionId());
                    }
                    return persisted;
                }

                public void complete(ToolInvocationRecord record) {
                    if (record.toolCallId().equals("complete-failure")) {
                        delegate.complete(
                                new ToolInvocationRecord(
                                        record.sessionId(),
                                        record.turnId(),
                                        record.iteration(),
                                        record.toolCallId(),
                                        record.toolName(),
                                        record.source(),
                                        record.inputJson(),
                                        null,
                                        record.resultChars(),
                                        record.resultTruncated(),
                                        "unconfirmed",
                                        "测试complete未确认",
                                        record.idempotent(),
                                        record.attempts(),
                                        record.durationMs()));
                        throw new AuditWriteException("测试complete未确认", true);
                    }
                    delegate.complete(record);
                }

                public void skipped(ToolInvocationRecord record) {
                    delegate.skipped(record);
                }
            };
        }
    }
}
