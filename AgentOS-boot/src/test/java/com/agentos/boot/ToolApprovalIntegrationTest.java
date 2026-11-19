package com.agentos.boot;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentos.boot.support.LocalModelServer;
import com.agentos.channel.cli.TerminalInputBroker;
import com.agentos.core.interaction.TurnRuntimeCapabilities;
import com.agentos.core.turn.AgentService;
import com.agentos.core.turn.ProcessRequest;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

/**
 * 006 交互式授权：真实 Manager、PostgreSQL 与 CLI 输入 broker。
 *
 * <p>工作区写入、任意程序、未列入白名单的域名都要用户确认；"本会话不再询问"按会话生效；拒绝原因回给模型；
 * 无人值守入口零动作；等待授权期间整轮计时暂停。
 */
@Tag("db")
@ExtendWith(BootDatabaseTestEnvironment.class)
@Testcontainers
@SpringBootTest(
        classes = AgentOSApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.main.web-application-type=none", "spring.config.import="})
class ToolApprovalIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    DockerImageName.parse("pgvector/pgvector:pg17")
                            .asCompatibleSubstituteFor("postgres"));

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static Path workspace;
    private static LocalModelServer model;
    private static HttpServer site;
    private static final AtomicInteger siteHits = new AtomicInteger();

    @BeforeAll
    static void prepare() throws Exception {
        workspace = Files.createTempDirectory("approval-acceptance-").toRealPath();
        var agent = Files.createDirectories(workspace.resolve("agents/main"));
        // turn_timeout 只有 2 秒：用来验证等待人工授权不计入整轮计时。
        Files.writeString(
                agent.resolve("AGENT.md"),
                "---\nname: main\nprovider:\n  model: test-model\n"
                        + "tools: [write_file, shell, http_get]\n"
                        + "settings:\n  turn_timeout: 2s\n---\n测试授权。\n");
        model = LocalModelServer.start();
        site = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        site.createContext(
                "/page",
                exchange -> {
                    siteHits.incrementAndGet();
                    byte[] body = "page-body".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        site.start();
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
        registry.add("agentos.providers.primary.api-key", () -> "approval-fixture-key");
        registry.add("agentos.embedding.enabled", () -> false);
    }

    /** 阻塞式键盘输入；不用 PipedReader，因为写线程结束后它会把管道当作失效。 */
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

    /** 一个 CLI 会话：同一 userId 即同一 active 会话；看到授权框后再输入。 */
    private static final class CliSession implements AutoCloseable {
        final String user = UUID.randomUUID().toString();
        final Keyboard keyboard = new Keyboard();
        final StringWriter screen = new StringWriter();
        final TerminalInputBroker broker =
                new TerminalInputBroker(keyboard, new PrintWriter(screen, true));

        /** 等提示出现后再输入，模拟用户看到授权框后作答。 */
        void answerWhenPrompted(String line, long delayMillis) {
            int before = prompts();
            Thread.ofVirtual()
                    .start(() -> {
                        try {
                            long until = System.nanoTime() + 10_000_000_000L;
                            while (prompts() <= before && System.nanoTime() < until) {
                                Thread.sleep(10);
                            }
                            Thread.sleep(delayMillis);
                            keyboard.type(line + "\n");
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        }
                    });
        }

        int prompts() {
            return screen.toString().split("需要授权：", -1).length - 1;
        }

        @Override
        public void close() {
            broker.close();
            keyboard.close();
        }
    }

    private static com.agentos.core.turn.TurnResult run(
            ApplicationContext context,
            CliSession cli,
            String id,
            String tool,
            Map<String, ?> args) {
        model.reset(
                toolCall(id, tool, JSON.writeValueAsString(args)),
                LocalModelServer.textResponse("完成"));
        return context.getBean(AgentService.class)
                .process(
                        new ProcessRequest("cli", cli.user, "main", "测试授权", "cli"),
                        TurnSink.NOOP,
                        new TurnRuntimeCapabilities(cli.broker));
    }

    @Test
    void writeNeedsConfirmationSessionGrantSkipsLaterPromptsAndFeedbackReachesModel(
            ApplicationContext context) throws Exception {
        try (var cli = new CliSession()) {
            cli.answerWhenPrompted("1", 0);
            var once =
                    run(
                            context,
                            cli,
                            "w-once",
                            "write_file",
                            Map.of("path", "once.txt", "content", "一次"));
            assertThat(once.status()).isEqualTo(TurnStatus.SUCCEEDED);
            assertThat(Files.readString(workspace.resolve("once.txt"))).isEqualTo("一次");
            assertThat(cli.screen.toString()).contains("需要授权：写入文件", "文件  once.txt", "方式  新建");

            cli.answerWhenPrompted("2", 0);
            run(context, cli, "w-grant", "write_file", Map.of("path", "a.txt", "content", "a"));
            int promptsAfterGrant = cli.prompts();
            run(context, cli, "w-again", "write_file", Map.of("path", "b.txt", "content", "b"));
            // 同一会话第二次写入不再询问。
            assertThat(cli.prompts()).isEqualTo(promptsAfterGrant);
            assertThat(workspace.resolve("b.txt")).exists();
        }
        try (var other = new CliSession()) {
            other.answerWhenPrompted("请改写到 notes 目录", 0);
            run(
                    context,
                    other,
                    "w-deny",
                    "write_file",
                    Map.of("path", "denied.txt", "content", "x"));
            // 会话授权不跨会话；拒绝原因原样回给模型，文件未写入。
            assertThat(other.prompts()).isEqualTo(1);
            assertThat(workspace.resolve("denied.txt")).doesNotExist();
            assertThat(model.requests().getLast()).contains("用户说明：请改写到 notes 目录");
        }
    }

    @Test
    void shellAndHttpAskBeforeActingAndUnattendedEntryDoesNothing(ApplicationContext context)
            throws Exception {
        try (var cli = new CliSession()) {
            cli.answerWhenPrompted("1", 0);
            run(
                    context,
                    cli,
                    "sh",
                    "shell",
                    Map.of("executable", "echo", "args", List.of("approved-shell")));
            assertThat(cli.screen.toString()).contains("需要授权：执行命令", "approved-shell");
            assertThat(model.requests().getLast()).contains("approved-shell");

            int before = siteHits.get();
            String url = "http://127.0.0.1:" + site.getAddress().getPort() + "/page";
            cli.answerWhenPrompted("1", 0);
            run(context, cli, "get", "http_get", Map.of("url", url));
            assertThat(siteHits.get()).isEqualTo(before + 1);
            assertThat(model.requests().getLast()).contains("page-body");
        }
        int before = siteHits.get();
        String url = "http://127.0.0.1:" + site.getAddress().getPort() + "/page";
        var unattended = new CliSession();
        try {
            model.reset(
                    toolCall("unattended", "http_get", JSON.writeValueAsString(Map.of("url", url))),
                    LocalModelServer.textResponse("完成"));
            var result =
                    context.getBean(AgentService.class)
                            .process(
                                    new ProcessRequest(
                                            "cli", unattended.user, "main", "无人值守", "cli"),
                                    TurnSink.NOOP,
                                    TurnRuntimeCapabilities.NONE);
            assertThat(result.status()).isEqualTo(TurnStatus.SUCCEEDED);
            assertThat(siteHits.get()).isEqualTo(before);
            assertThat(model.requests().getLast()).contains("无人值守");
        } finally {
            unattended.close();
        }
    }

    @Test
    void runLogsFollowTheTurnFromStartToEndWithoutContent(ApplicationContext context)
            throws Exception {
        var appender =
                new ch.qos.logback.core.read.ListAppender<
                        ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        var root =
                (ch.qos.logback.classic.Logger)
                        org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        root.addAppender(appender);
        try (var cli = new CliSession()) {
            cli.answerWhenPrompted("1", 0);
            run(
                    context,
                    cli,
                    "log-write",
                    "write_file",
                    Map.of("path", "log-check.txt", "content", "BODY_SENTINEL_不应出现在日志"));
        } finally {
            root.detachAppender(appender);
        }
        var messages =
                appender.list.stream()
                        .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                        .toList();
        // 同一轮的日志按发生顺序排列，覆盖起止、模型调用、授权和工具调用。
        var expected =
                List.of(
                        "轮次开始 agent=main",
                        "第 1/10 次模型调用开始",
                        "第 1 次模型调用返回 toolCalls=1 tools=[write_file]",
                        "工具调用开始 tool=write_file",
                        "工具授权 type=FILE_WRITE outcome=ALLOW_ONCE waitedMs=",
                        "工具调用结束 tool=write_file",
                        "第 2/10 次模型调用开始",
                        "第 2 次模型调用返回 toolCalls=0 answerChars=",
                        "轮次结束 status=SUCCEEDED");
        int from = 0;
        for (String fragment : expected) {
            int found = -1;
            for (int i = from; i < messages.size(); i++) {
                if (messages.get(i).contains(fragment)) {
                    found = i;
                    break;
                }
            }
            assertThat(found).as("日志缺失或顺序不对：" + fragment).isGreaterThanOrEqualTo(0);
            from = found + 1;
        }
        assertThat(messages.stream().filter(m -> m.contains("工具调用结束")).findFirst().orElse(""))
                .contains("status=success", "attempts=1", "durationMs=");
        assertThat(String.join("\n", messages)).doesNotContain("BODY_SENTINEL", "log-check.txt");
    }

    @Test
    void waitingForApprovalDoesNotConsumeTheTurnTimeout(ApplicationContext context)
            throws Exception {
        try (var cli = new CliSession()) {
            // 用户 3 秒后才作答，超过 2 秒的 turn_timeout。
            cli.answerWhenPrompted("1", 3000);
            var result =
                    run(
                            context,
                            cli,
                            "slow",
                            "write_file",
                            Map.of("path", "slow.txt", "content", "慢"));
            assertThat(result.status()).isEqualTo(TurnStatus.SUCCEEDED);
            assertThat(Files.readString(workspace.resolve("slow.txt"))).isEqualTo("慢");
        }
    }

    private static String toolCall(String id, String tool, String arguments) {
        return JSON.writeValueAsString(
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
                                                                        arguments)))),
                                        "finish_reason",
                                        "tool_calls"))));
    }
}
