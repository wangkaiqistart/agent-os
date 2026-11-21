package com.agentos.boot;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentos.boot.support.LocalModelServer;
import com.agentos.boot.support.RunEventAssertions;
import com.agentos.channel.cli.TerminalInputBroker;
import com.agentos.core.interaction.TurnRuntimeCapabilities;
import com.agentos.core.turn.AgentService;
import com.agentos.core.turn.ProcessRequest;
import com.agentos.core.turn.TurnEvent;
import com.agentos.core.turn.TurnResult;
import com.agentos.core.turn.TurnSink;
import com.agentos.core.turn.TurnStatus;
import com.sun.net.httpserver.HttpServer;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
 * 007 用户取消：PostgreSQL + 真实 Manager + 治理链。
 *
 * <p>在只读工具执行中、有副作用工具执行中、等待确认中三个时机调用 {@link AgentService#cancel(UUID)}：
 * 本轮以 CANCELLED 落库、被中止的工具记为 interrupted、会话租约释放、同一会话可以立即开始下一轮。
 * 耗时、库内状态与终态行写到 target/007-demo/cancel.txt 供演示记录摘录。
 */
@Tag("db")
@ExtendWith(BootDatabaseTestEnvironment.class)
@Testcontainers
@SpringBootTest(
        classes = AgentOSApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.main.web-application-type=none", "spring.config.import="})
class TurnCancelIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    DockerImageName.parse("pgvector/pgvector:pg17")
                            .asCompatibleSubstituteFor("postgres"));

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Path DEMO = Path.of("target", "007-demo");
    private static final StringBuilder REPORT = new StringBuilder();
    private static Path workspace;
    private static LocalModelServer model;
    private static HttpServer site;

    @jakarta.annotation.Resource AgentService service;
    @jakarta.annotation.Resource JdbcTemplate jdbc;

    @BeforeAll
    static void prepare() throws Exception {
        workspace = Files.createTempDirectory("turn-cancel-").toRealPath();
        Path dir = Files.createDirectories(workspace.resolve("agents/main"));
        Files.writeString(
                dir.resolve("AGENT.md"),
                "---\nname: main\nprovider:\n  model: test-model\n"
                        + "tools: [http_get, write_file, shell]\n"
                        + "settings:\n  turn_timeout: 60s\n---\n取消测试。\n");
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
                            Thread.sleep(20_000);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                        exchange.getResponseBody().write(body);
                    }
                });
        site.start();
        Files.createDirectories(DEMO);
    }

    @AfterAll
    static void stop() throws Exception {
        if (model != null) {
            model.close();
        }
        if (site != null) {
            site.stop(0);
        }
        Files.writeString(DEMO.resolve("cancel.txt"), REPORT.toString());
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("agentos.workspace.root", () -> workspace.toString());
        registry.add("agentos.providers.primary.base-url", () -> model.baseUrl() + "/v1");
        registry.add("agentos.providers.primary.api-key", () -> "cancel-fixture-key");
        registry.add("agentos.embedding.enabled", () -> false);
        registry.add("agentos.tools.http.allowed-domains", () -> "127.0.0.1");
    }

    /** 阻塞式键盘：只有测试明确输入时才有字符，用来模拟"用户一直没回答"。 */
    private static final class Keyboard extends Reader {
        private final java.util.concurrent.LinkedBlockingQueue<Integer> chars =
                new java.util.concurrent.LinkedBlockingQueue<>();

        void type(String line) {
            line.chars().forEach(chars::add);
        }

        @Override
        public int read(char[] buffer, int offset, int length) throws java.io.IOException {
            if (length == 0) {
                return 0;
            }
            try {
                int next = chars.take();
                if (next < 0) {
                    return -1;
                }
                buffer[offset] = (char) next;
                return 1;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new java.io.InterruptedIOException();
            }
        }

        @Override
        public void close() {
            chars.add(-1);
        }
    }

    /** 记录事件；看到指定事件类型后过 delay 毫秒取消本轮。 */
    private final class CancellingRecorder implements TurnSink {
        final List<TurnEvent> events = new CopyOnWriteArrayList<>();
        final String trigger;
        volatile long cancelledAt;

        CancellingRecorder(String trigger) {
            this.trigger = trigger;
        }

        @Override
        public void onEvent(TurnEvent event) {
            events.add(event);
            if (event.type().equals(trigger) && cancelledAt == 0) {
                UUID turnId = event.turnId();
                Thread.ofVirtual()
                        .start(() -> {
                            try {
                                Thread.sleep(300);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            cancelledAt = System.nanoTime();
                            assertThat(service.cancel(turnId)).isTrue();
                        });
            }
        }
    }

    private static String toolCall(String id, String tool, Map<String, ?> args) {
        return JSON.writeValueAsString(
                Map.of(
                        "id", "cancel",
                        "object", "chat.completion",
                        "model", "test-model",
                        "choices",
                                List.of(
                                        Map.of(
                                                "index",
                                                0,
                                                "message",
                                                Map.of(
                                                        "role", "assistant",
                                                        "content", "",
                                                        "tool_calls",
                                                                List.of(
                                                                        Map.of(
                                                                                "id",
                                                                                id,
                                                                                "type",
                                                                                "function",
                                                                                "function",
                                                                                Map.of(
                                                                                        "name",
                                                                                        tool,
                                                                                        "arguments",
                                                                                        JSON
                                                                                                .writeValueAsString(
                                                                                                        args))))),
                                                "finish_reason",
                                                "tool_calls")),
                        "usage",
                                Map.of(
                                        "prompt_tokens",
                                        1,
                                        "completion_tokens",
                                        2,
                                        "total_tokens",
                                        3)));
    }

    private TurnResult cancelScenario(
            String name,
            String user,
            String trigger,
            TurnRuntimeCapabilities capabilities,
            String expectedToolStatus,
            String... reply) {
        model.reset(reply);
        var recorder = new CancellingRecorder(trigger);
        TurnResult result =
                service.process(
                        new ProcessRequest("cli", user, "main", "执行", "cli"),
                        recorder,
                        capabilities);
        long cancelToEnd = Duration.ofNanos(System.nanoTime() - recorder.cancelledAt).toMillis();

        RunEventAssertions.assertWellFormed(recorder.events);
        assertThat(result.status()).as(name).isEqualTo(TurnStatus.CANCELLED);
        assertThat(cancelToEnd).as(name + " 取消后结束 ms").isLessThan(2000);
        assertThat(model.calls()).as(name + " 取消后不再调用模型").isOne();
        assertThat(
                        jdbc.queryForObject(
                                "select status from agent_turns where turn_id=?",
                                String.class,
                                result.turnId()))
                .isEqualTo("CANCELLED");
        assertThat(
                        jdbc.queryForList(
                                "select status from tool_invocations where turn_id=?",
                                String.class,
                                result.turnId()))
                .containsExactly(expectedToolStatus);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from sessions where user_id=? and lease_owner is not null",
                                Integer.class,
                                user))
                .as(name + " 租约已释放")
                .isZero();
        REPORT.append("## ")
                .append(name)
                .append("\n取消到结束：")
                .append(cancelToEnd)
                .append(" ms；agent_turns.status=CANCELLED；tool_invocations.status=")
                .append(expectedToolStatus)
                .append("；返回文本：")
                .append(result.text())
                .append("\n")
                .append(
                        recorder.events.stream()
                                .map(e -> e.seq() + " " + e.type())
                                .collect(Collectors.joining(" → ")))
                .append("\n\n");
        return result;
    }

    @Test
    void cancelDuringReadOnlyTool() {
        String user = UUID.randomUUID().toString();
        String url = "http://127.0.0.1:" + site.getAddress().getPort() + "/slow";
        var result =
                cancelScenario(
                        "只读工具执行中",
                        user,
                        "tool.started",
                        TurnRuntimeCapabilities.NONE,
                        "interrupted",
                        LocalModelServer.toolResponse("get-slow", url));
        assertThat(result.text()).isEqualTo("本轮已取消");
        // 同一会话可以立即开始下一轮。
        model.reset(LocalModelServer.textResponse("继续"));
        var next = service.process(new ProcessRequest("cli", user, "main", "继续", "cli"));
        assertThat(next.status()).isEqualTo(TurnStatus.SUCCEEDED);
    }

    @Test
    void cancelDuringSideEffectToolSaysItMayHaveTakenEffect() {
        var keyboard = new Keyboard();
        keyboard.type("1\n");
        try (var broker =
                new TerminalInputBroker(keyboard, new PrintWriter(new StringWriter(), true))) {
            var result =
                    cancelScenario(
                            "有副作用工具执行中",
                            UUID.randomUUID().toString(),
                            "approval.resolved",
                            new TurnRuntimeCapabilities(broker),
                            "interrupted",
                            toolCall(
                                    "sh-sleep",
                                    "shell",
                                    Map.of("executable", "sleep", "args", List.of("20"))));
            assertThat(result.text()).contains("可能已生效");
        } finally {
            keyboard.close();
        }
    }

    @Test
    void cancelWhileWaitingForApprovalNeverWrites() {
        var keyboard = new Keyboard();
        try (var broker =
                new TerminalInputBroker(keyboard, new PrintWriter(new StringWriter(), true))) {
            var result =
                    cancelScenario(
                            "等待确认中",
                            UUID.randomUUID().toString(),
                            "approval.requested",
                            new TurnRuntimeCapabilities(broker),
                            "interrupted",
                            toolCall(
                                    "w-wait",
                                    "write_file",
                                    Map.of("path", "never.txt", "content", "x")));
            assertThat(workspace.resolve("never.txt")).doesNotExist();
            // 第一次授权就被取消：动作确定没有发生，不提示"可能已生效"。
            assertThat(result.text()).isEqualTo("本轮已取消");
        } finally {
            keyboard.close();
        }
    }
}
