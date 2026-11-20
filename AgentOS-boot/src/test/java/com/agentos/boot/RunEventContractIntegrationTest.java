package com.agentos.boot;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentos.boot.support.LocalModelServer;
import com.agentos.boot.support.RunEventAssertions;
import com.agentos.channel.cli.TerminalInputBroker;
import com.agentos.core.interaction.TurnRuntimeCapabilities;
import com.agentos.core.turn.AgentService;
import com.agentos.core.turn.ProcessRequest;
import com.agentos.core.turn.TurnEvent;
import com.agentos.core.turn.TurnEventPayload;
import com.agentos.core.turn.TurnResult;
import com.agentos.core.turn.TurnSink;
import com.agentos.core.turn.TurnStatus;
import com.sun.net.httpserver.HttpServer;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * 007 运行事件契约：PostgreSQL + 真实注入的 Manager + 治理链，覆盖 10 类场景的事件序列。
 *
 * <p>每类场景都校验顺序语法、终态与库内状态一致、模型请求的每个工具调用恰好一对开始 / 结束事件且对数与
 * tool_invocations 行数相等，以及事件中不出现原始密钥。事件序列写到 target/007-demo/run-events 供演示记录摘录。
 */
@Tag("db")
@ExtendWith(BootDatabaseTestEnvironment.class)
@Testcontainers
@SpringBootTest(
        classes = AgentOSApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.main.web-application-type=none", "spring.config.import="})
class RunEventContractIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    DockerImageName.parse("pgvector/pgvector:pg17")
                            .asCompatibleSubstituteFor("postgres"));

    static final String SECRET = "contract-secret-key-91c2";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Path DEMO = Path.of("target", "007-demo", "run-events");
    private static Path workspace;
    private static LocalModelServer model;
    private static HttpServer site;

    @jakarta.annotation.Resource AgentService service;
    @jakarta.annotation.Resource JdbcTemplate jdbc;

    @BeforeAll
    static void prepare() throws Exception {
        workspace = Files.createTempDirectory("run-event-contract-").toRealPath();
        agent("main", "[read_file, write_file, http_get]", "turn_timeout: 10s");
        agent("slow", "[http_get]", "turn_timeout: 1s");
        agent("once", "[read_file]", "max_iterations: 1");
        Files.writeString(workspace.resolve("present.txt"), "present");
        model = LocalModelServer.start();
        site = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        site.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        site.createContext(
                "/slow",
                exchange -> {
                    try (exchange) {
                        byte[] body = "slow".getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, body.length);
                        try {
                            Thread.sleep(1500);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                        exchange.getResponseBody().write(body);
                    }
                });
        site.start();
        Files.createDirectories(DEMO);
    }

    private static void agent(String name, String tools, String settings) throws Exception {
        Path dir = Files.createDirectories(workspace.resolve("agents/" + name));
        Files.writeString(
                dir.resolve("AGENT.md"),
                "---\nname: "
                        + name
                        + "\nprovider:\n  model: test-model\ntools: "
                        + tools
                        + "\nsettings:\n  "
                        + settings
                        + "\n---\n事件契约测试。\n");
    }

    @AfterAll
    static void stop() {
        if (model != null) {
            model.close();
        }
        if (site != null) {
            site.stop(0);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("agentos.workspace.root", () -> workspace.toString());
        registry.add("agentos.providers.primary.base-url", () -> model.baseUrl() + "/v1");
        registry.add("agentos.providers.primary.api-key", () -> SECRET);
        registry.add("agentos.embedding.enabled", () -> false);
        registry.add("agentos.tools.http.allowed-domains", () -> "127.0.0.1");
        registry.add("spring.ai.tools.limits.max-calls-per-tool.read_file", () -> "2");
    }

    /** 记录全部事件的输出端；不走流式，因为本地替身只返回完整 JSON。 */
    private static final class Recorder implements TurnSink {
        final List<TurnEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void onEvent(TurnEvent event) {
            events.add(event);
        }

        List<String> types() {
            return events.stream().map(TurnEvent::type).toList();
        }

        <T extends TurnEventPayload> List<T> of(Class<T> type) {
            return events.stream()
                    .map(TurnEvent::payload)
                    .filter(type::isInstance)
                    .map(type::cast)
                    .toList();
        }
    }

    static String reply(String content, List<Map<String, Object>> calls, String finishReason) {
        var message = new java.util.LinkedHashMap<String, Object>();
        message.put("role", "assistant");
        message.put("content", content);
        if (!calls.isEmpty()) {
            message.put("tool_calls", calls);
        }
        return JSON.writeValueAsString(
                Map.of(
                        "id", "contract",
                        "object", "chat.completion",
                        "model", "test-model",
                        "choices",
                                List.of(
                                        Map.of(
                                                "index", 0,
                                                "message", message,
                                                "finish_reason", finishReason)),
                        "usage",
                                Map.of(
                                        "prompt_tokens", 1,
                                        "completion_tokens", 2,
                                        "total_tokens", 3)));
    }

    static Map<String, Object> call(String id, String tool, Map<String, ?> args) {
        return Map.of(
                "id",
                id,
                "type",
                "function",
                "function",
                Map.of("name", tool, "arguments", JSON.writeValueAsString(args)));
    }

    private Recorder run(String scenario, String agent, TurnRuntimeCapabilities capabilities) {
        var recorder = new Recorder();
        TurnResult result =
                service.process(
                        new ProcessRequest(
                                "test", UUID.randomUUID().toString(), agent, "执行", "test"),
                        recorder,
                        capabilities);
        verifyContract(scenario, result, recorder);
        return recorder;
    }

    private void verifyContract(String scenario, TurnResult result, Recorder recorder) {
        RunEventAssertions.assertWellFormed(recorder.events);
        String stored =
                jdbc.queryForObject(
                        "select status from agent_turns where turn_id=?",
                        String.class,
                        result.turnId());
        assertThat(stored).as(scenario).isEqualTo(result.status().name());
        var terminal = recorder.events.getLast().payload();
        switch (result.status()) {
            case SUCCEEDED ->
                    assertThat(terminal).isInstanceOf(TurnEventPayload.RunCompleted.class);
            case CANCELLED ->
                    assertThat(terminal).isInstanceOf(TurnEventPayload.RunCancelled.class);
            default ->
                    assertThat(terminal)
                            .isInstanceOfSatisfying(
                                    TurnEventPayload.RunFailed.class,
                                    failed ->
                                            assertThat(failed.status()).isEqualTo(result.status()));
        }
        Integer rows =
                jdbc.queryForObject(
                        "select count(*) from tool_invocations where turn_id=?",
                        Integer.class,
                        result.turnId());
        assertThat(recorder.of(TurnEventPayload.ToolStarted.class)).hasSize(rows);
        assertThat(recorder.of(TurnEventPayload.ToolFinished.class)).hasSize(rows);
        String dump =
                recorder.events.stream()
                        .map(e -> e.seq() + " " + e.type() + " " + e.payload())
                        .collect(Collectors.joining("\n"));
        assertThat(dump).as(scenario + " 不含原始密钥").doesNotContain(SECRET);
        try {
            Files.writeString(DEMO.resolve(scenario + ".txt"), dump + "\n");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static TurnEventPayload.ToolFinished onlyTool(Recorder recorder) {
        return recorder.of(TurnEventPayload.ToolFinished.class).getFirst();
    }

    @Test
    void success() {
        model.reset(LocalModelServer.textResponse("完成"));
        var recorder = run("01-success", "main", TurnRuntimeCapabilities.NONE);
        assertThat(recorder.types())
                .containsExactly(
                        "run.started",
                        "step.started",
                        "message.started",
                        "message.delta",
                        "message.ended",
                        "step.finished",
                        "run.completed");
    }

    @Test
    void toolFailureIsReportedAndTurnContinues() {
        model.reset(
                reply(
                        "",
                        List.of(
                                call(
                                        "c-missing",
                                        "read_file",
                                        Map.of("path", "missing-" + SECRET + ".txt"))),
                        "tool_calls"),
                LocalModelServer.textResponse("文件不存在"));
        var recorder = run("02-tool-failure", "main", TurnRuntimeCapabilities.NONE);
        assertThat(onlyTool(recorder).status()).isEqualTo(TurnEventPayload.ToolStatus.FAILED);
        assertThat(recorder.events.getLast().payload())
                .isInstanceOf(TurnEventPayload.RunCompleted.class);
    }

    @Test
    void approvalDeniedClosesInsideTool() {
        model.reset(
                reply(
                        "",
                        List.of(
                                call(
                                        "c-write",
                                        "write_file",
                                        Map.of("path", "x.txt", "content", SECRET))),
                        "tool_calls"),
                LocalModelServer.textResponse("已放弃写入"));
        var screen = new StringWriter();
        try (var broker =
                new TerminalInputBroker(new StringReader("n\n"), new PrintWriter(screen, true))) {
            var recorder = run("03-approval-denied", "main", new TurnRuntimeCapabilities(broker));
            assertThat(recorder.of(TurnEventPayload.ApprovalResolved.class))
                    .singleElement()
                    .satisfies(r ->
                            assertThat(r.decision())
                                    .isEqualTo(TurnEventPayload.ApprovalDecisionKind.DENY));
            assertThat(onlyTool(recorder).status()).isEqualTo(TurnEventPayload.ToolStatus.DENIED);
            assertThat(workspace.resolve("x.txt")).doesNotExist();
        }
    }

    @Test
    void unattendedApprovalIsDeniedWithoutAction() {
        model.reset(
                reply(
                        "",
                        List.of(
                                call(
                                        "c-write",
                                        "write_file",
                                        Map.of("path", "y.txt", "content", "y"))),
                        "tool_calls"),
                LocalModelServer.textResponse("无人值守"));
        var recorder = run("04-unattended", "main", TurnRuntimeCapabilities.NONE);
        assertThat(recorder.of(TurnEventPayload.ApprovalResolved.class))
                .singleElement()
                .satisfies(r ->
                        assertThat(r.decision())
                                .isEqualTo(TurnEventPayload.ApprovalDecisionKind.UNATTENDED));
        assertThat(onlyTool(recorder).status()).isEqualTo(TurnEventPayload.ToolStatus.DENIED);
        assertThat(workspace.resolve("y.txt")).doesNotExist();
    }

    @Test
    void timeoutFailsWithCode() {
        model.reset(
                LocalModelServer.toolResponse(
                        "c-slow", "http://127.0.0.1:" + site.getAddress().getPort() + "/slow"));
        var recorder = run("05-timeout", "slow", TurnRuntimeCapabilities.NONE);
        assertThat(((TurnEventPayload.RunFailed) recorder.events.getLast().payload()).code())
                .isEqualTo(com.agentos.core.turn.TurnFailureCode.TIMED_OUT);
    }

    @Test
    void maxIterationsFailsWithCode() {
        model.reset(
                reply(
                        "",
                        List.of(call("c-read", "read_file", Map.of("path", "present.txt"))),
                        "tool_calls"));
        var recorder = run("06-max-iterations", "once", TurnRuntimeCapabilities.NONE);
        var failed = (TurnEventPayload.RunFailed) recorder.events.getLast().payload();
        assertThat(failed.status()).isEqualTo(TurnStatus.MAX_ITERATIONS);
        assertThat(failed.code()).isEqualTo(com.agentos.core.turn.TurnFailureCode.MAX_ITERATIONS);
    }

    @Test
    void toolLimitSkipsExtraCalls() {
        model.reset(
                reply(
                        "",
                        List.of(
                                call("c-1", "read_file", Map.of("path", "present.txt")),
                                call("c-2", "read_file", Map.of("path", "present.txt")),
                                call("c-3", "read_file", Map.of("path", "present.txt"))),
                        "tool_calls"),
                LocalModelServer.textResponse("读完了"));
        var recorder = run("07-tool-limit", "main", TurnRuntimeCapabilities.NONE);
        assertThat(recorder.of(TurnEventPayload.ToolFinished.class))
                .extracting(TurnEventPayload.ToolFinished::status)
                .contains(TurnEventPayload.ToolStatus.SKIPPED);
    }

    @Test
    void truncatedResponseFailsWithoutRunningTools() {
        model.reset(
                reply(
                        "半截",
                        List.of(call("c-cut", "read_file", Map.of("path", "present.txt"))),
                        "length"));
        var recorder = run("08-truncated", "main", TurnRuntimeCapabilities.NONE);
        assertThat(recorder.types()).noneMatch(type -> type.startsWith("tool."));
        assertThat(((TurnEventPayload.RunFailed) recorder.events.getLast().payload()).code())
                .isEqualTo(com.agentos.core.turn.TurnFailureCode.OUTPUT_TRUNCATED);
        assertThat(model.calls()).isOne();
    }

    @Test
    void filteredResponseFailsWithCode() {
        model.reset(reply("", List.of(), "content_filter"));
        var recorder = run("09-filtered", "main", TurnRuntimeCapabilities.NONE);
        assertThat(((TurnEventPayload.RunFailed) recorder.events.getLast().payload()).code())
                .isEqualTo(com.agentos.core.turn.TurnFailureCode.CONTENT_FILTERED);
    }
}
