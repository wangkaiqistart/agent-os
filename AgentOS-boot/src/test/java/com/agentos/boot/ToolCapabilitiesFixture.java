package com.agentos.boot;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentos.boot.support.LocalModelServer;
import com.agentos.core.audit.AuditWriteException;
import com.agentos.core.audit.ToolInvocationRecord;
import com.agentos.core.audit.ToolInvocationRecorder;
import com.agentos.core.config.AuditProperties;
import com.agentos.core.interaction.InteractionPort;
import com.agentos.core.interaction.InteractionResult;
import com.agentos.core.interaction.TurnRuntimeCapabilities;
import com.agentos.core.tool.Redactor;
import com.agentos.core.turn.AgentService;
import com.agentos.core.turn.ProcessRequest;
import com.agentos.core.turn.TurnBudgetRegistry;
import com.agentos.core.turn.TurnResult;
import com.agentos.core.turn.TurnSink;
import com.agentos.storage.audit.AuditTransactions;
import com.agentos.storage.audit.JpaToolInvocationRecorder;
import com.agentos.storage.session.ToolInvocationRepository;
import com.sun.net.httpserver.HttpServer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

/** 全程验收共享隔离夹具；始终保留真实Provider协议、Manager与PostgreSQL。 */
@Tag("db")
@ExtendWith(BootDatabaseTestEnvironment.class)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
        classes = {AgentOSApplication.class, ToolCapabilitiesFixture.Configuration.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.main.web-application-type=none", "spring.config.import="})
abstract class ToolCapabilitiesFixture {
    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    DockerImageName.parse("pgvector/pgvector:pg17")
                            .asCompatibleSubstituteFor("postgres"));

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final List<String> TOOLS =
            List.of(
                    "http_get",
                    "read_file",
                    "write_file",
                    "list_dir",
                    "shell",
                    "http_post",
                    "edit_file",
                    "grep",
                    "glob",
                    "ask_user",
                    "web_search");
    static Path workspace;
    static Path python;
    static Path control;
    static Path stats;
    static LocalModelServer model;
    static HttpServer server;
    static ExecutorService handlers;
    static final AtomicInteger network = new AtomicInteger();
    static final AtomicInteger active = new AtomicInteger();
    static final AtomicInteger accesses = new AtomicInteger();
    static final AtomicInteger prompts = new AtomicInteger();
    static volatile boolean slow;
    static final String SECRET = "capability-private-sentinel";

    @BeforeAll
    static void prepareCapabilities() throws Exception {
        workspace = Files.createTempDirectory("capabilities-");
        python = Path.of("/usr/bin/python3").toRealPath();
        control = workspace.resolve("control.json");
        stats = workspace.resolve("stats.json");
        Files.writeString(control, "{}");
        Path mcpScript =
                Path.of("../specs/005-expand-tool-capabilities/fixtures/mcp_test_server.py")
                        .toRealPath();
        Files.writeString(
                workspace.resolve("mcp_servers.yaml"),
                "servers:\n  - name: fixture\n    transport: stdio\n    command: "
                        + JSON.writeValueAsString(python.toString())
                        + "\n    args: "
                        + JSON.writeValueAsString(
                                List.of(
                                        mcpScript.toString(),
                                        "--transport",
                                        "stdio",
                                        "--control",
                                        control.toString(),
                                        "--stats",
                                        stats.toString(),
                                        "--ready",
                                        workspace.resolve("ready.json").toString()))
                        + "\n    cwd: "
                        + JSON.writeValueAsString(workspace.toString())
                        + "\n");
        for (String name : List.of("allowed", "denied", "remote")) {
            Path agent = Files.createDirectories(workspace.resolve("agents/" + name));
            String tools =
                    name.equals("denied")
                            ? "[]"
                            : name.equals("remote")
                                    ? "[mcp_fixture__echo]"
                                    : JSON.writeValueAsString(TOOLS);
            Files.writeString(
                    agent.resolve("AGENT.md"),
                    "---\nname: "
                            + name
                            + "\nprovider:\n  model: test-model\ntools: "
                            + tools
                            + "\nmcp_servers: [fixture]\n---\n使用已授权工具完成任务。\n");
        }
        server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        handlers = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(handlers);
        server.createContext(
                "/",
                exchange -> {
                    network.incrementAndGet();
                    active.incrementAndGet();
                    try {
                        exchange.getRequestBody().readAllBytes();
                        if (slow) {
                            exchange.sendResponseHeaders(200, 0);
                            while (true) {
                                exchange.getResponseBody().write(' ');
                                exchange.getResponseBody().flush();
                                Thread.sleep(10);
                            }
                        } else {
                            String body =
                                    exchange.getRequestURI().getPath().equals("/search")
                                            ? "{\"web\":{\"results\":[{\"title\":\"资料\",\"url\":\"https://example.org/item\",\"description\":\""
                                                    + SECRET
                                                    + " 技术资料\"}]}}"
                                            : "北京晴，温度22摄氏度。 " + SECRET;
                            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                            exchange.sendResponseHeaders(200, bytes.length);
                            exchange.getResponseBody().write(bytes);
                        }
                    } catch (java.io.IOException closed) {
                        // 客户端取消被服务端观察到；不能依赖外层stop替代该证据。
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } finally {
                        exchange.close();
                        active.decrementAndGet();
                    }
                });
        server.start();
        model = LocalModelServer.start();
        slow = false;
    }

    @AfterAll
    static void closeCapabilities() {
        model.close();
        server.stop(0);
        handlers.shutdownNow();
    }

    static String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("agentos.workspace.root", () -> workspace.toString());
        r.add("agentos.providers.primary.base-url", () -> model.baseUrl() + "/v1");
        r.add("agentos.providers.primary.api-key", () -> SECRET);
        r.add("agentos.embedding.enabled", () -> false);
        r.add("agentos.tools.http.allowed-domains[0]", () -> "127.0.0.1");
        r.add("agentos.tools.file.read-allowed-paths[0]", () -> workspace.toString());
        r.add("agentos.tools.file.write-allowed-paths[0]", () -> workspace.toString());
        r.add("agentos.tools.shell.commands[0].executable", () -> python.toString());
        r.add(
                "agentos.tools.shell.commands[0].env.DEPLOYMENT_TOKEN",
                () -> "shell-deployment-private");
        r.add("agentos.tools.shell.commands[0].allowed-cwds[0]", () -> workspace.toString());
        r.add("agentos.tools.search.endpoint", () -> url() + "/search");
        r.add("agentos.tools.search.api-key", () -> SECRET);
    }

    static String response(String id, String tool, Map<String, Object> args) {
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
                                                                        JSON.writeValueAsString(
                                                                                args))))),
                                        "finish_reason",
                                        "tool_calls"))));
    }

    static Map<String, Object> arguments(String tool) {
        return switch (tool) {
            case "http_get" -> Map.of("url", url() + "/weather");
            case "http_post" ->
                    Map.of(
                            "url",
                            url() + "/weather",
                            "body",
                            "payload",
                            "content_type",
                            "text/plain");
            case "read_file" -> Map.of("path", "sample.txt");
            case "write_file" -> Map.of("path", "created.txt", "content", "created");
            case "list_dir" -> Map.of("path", ".");
            case "shell" ->
                    Map.of(
                            "executable",
                            python.toString(),
                            "args",
                            List.of(
                                    "-c",
                                    "from pathlib import Path; Path('action.txt').write_text('done'); print('done')"));
            case "edit_file" ->
                    Map.of("path", "sample.txt", "old_text", "target", "new_text", "changed");
            case "grep" -> Map.of("path", ".", "text", "target");
            case "glob" -> Map.of("path", ".", "pattern", "*.txt");
            case "ask_user" -> Map.of("question", "选择方案");
            case "web_search" -> Map.of("query", "技术资料");
            default -> Map.of("value", "business");
        };
    }

    static TurnResult run(
            org.springframework.context.ApplicationContext c,
            String tool,
            String mode,
            Map<String, Object> args,
            TurnSink sink) {
        model.reset(response(tool + "-" + mode, tool, args), LocalModelServer.textResponse("完成"));
        accesses.set(0);
        network.set(0);
        prompts.set(0);
        InteractionPort port =
                (request, scope) -> {
                    prompts.incrementAndGet();
                    return new InteractionResult(InteractionResult.Status.ANSWERED, "方案A");
                };
        return c.getBean(AgentService.class)
                .process(
                        new ProcessRequest(
                                "test",
                                UUID.randomUUID().toString(),
                                mode.equals("denied")
                                        ? "denied"
                                        : tool.startsWith("mcp_") ? "remote" : "allowed",
                                "测试能力",
                                "test"),
                        sink,
                        new TurnRuntimeCapabilities(port));
    }

    static void awaitRemote(org.springframework.context.ApplicationContext c) throws Exception {
        var catalog = c.getBean(com.agentos.core.tool.ToolCatalog.class);
        var profile = c.getBean(com.agentos.core.agent.AgentLoader.class).load("remote").profile();
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (catalog.resolve(profile).modelCallbacks().isEmpty() && System.nanoTime() < end) {
            Thread.sleep(10);
        }
        assertThat(catalog.resolve(profile).modelCallbacks()).hasSize(1);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean
        RepositoryTools repositoryTools() {
            return new RepositoryTools();
        }

        @Bean
        @org.springframework.context.annotation.Primary
        com.agentos.core.tool.Sandbox countedSandbox(
                com.agentos.tool.file.FileAccessPolicy files,
                com.agentos.tool.http.HttpToolProperties http,
                com.agentos.tool.process.ShellToolProperties shell) {
            var delegate =
                    new com.agentos.tool.sandbox.WhitelistSandbox(
                            http.allowedDomains(), files, shell);
            return action -> {
                accesses.incrementAndGet();
                delegate.enforce(action);
            };
        }

        @Bean
        ToolInvocationRecorder matrixRecorder(
                ToolInvocationRepository repository,
                Redactor redactor,
                AuditTransactions transactions,
                TurnBudgetRegistry budgets,
                AuditProperties properties,
                JdbcTemplate jdbc) {
            var delegate =
                    new JpaToolInvocationRecorder(
                            repository, redactor, transactions, budgets, properties);
            return new ToolInvocationRecorder() {
                public ToolInvocationRecord begin(ToolInvocationRecord record) {
                    if (record.toolCallId().endsWith("begin-failure")) {
                        throw new AuditWriteException("测试begin未确认", false);
                    }
                    var result = delegate.begin(record);
                    if (record.toolCallId().endsWith("cancel")) {
                        budgets.get(record.turnId()).cancel();
                    }
                    if (record.toolCallId().endsWith("lease")) {
                        jdbc.update(
                                "update sessions set lease_owner='other-fixture' where session_id=?",
                                record.sessionId());
                    }
                    return result;
                }

                public void complete(ToolInvocationRecord record) {
                    if (record.toolCallId().endsWith("complete-failure")) {
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

    static class RepositoryTools {
        @org.springframework.ai.tool.annotation.Tool(
                name = "repository_info",
                description = "读取隔离业务仓库的当前事实")
        @com.agentos.tool.annotation.ToolPolicy(idempotent = true)
        public String repositoryInfo() {
            return "仓库agent-os使用Java，开放问题3个，今日提交2个。";
        }
    }
}
