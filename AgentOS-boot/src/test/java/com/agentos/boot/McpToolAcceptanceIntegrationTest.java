package com.agentos.boot;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentos.boot.support.LocalModelServer;
import com.agentos.core.audit.AuditWriteException;
import com.agentos.core.audit.ToolInvocationRecord;
import com.agentos.core.audit.ToolInvocationRecorder;
import com.agentos.core.config.AuditProperties;
import com.agentos.core.tool.Redactor;
import com.agentos.core.turn.AgentService;
import com.agentos.core.turn.ProcessRequest;
import com.agentos.core.turn.TurnBudgetRegistry;
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

/** 三种MCP传输通过真实Manager和PostgreSQL验证双授权、原始来源及审计不重放。 */
@Tag("db")
@ExtendWith(BootDatabaseTestEnvironment.class)
@Testcontainers
@SpringBootTest(
        classes = {AgentOSApplication.class, McpToolAcceptanceIntegrationTest.Configuration.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.main.web-application-type=none", "spring.config.import="})
class McpToolAcceptanceIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    DockerImageName.parse("pgvector/pgvector:pg17")
                            .asCompatibleSubstituteFor("postgres"));

    private static Path workspace;
    private static LocalModelServer model;
    private static final java.util.Map<String, Path> fixtures = new java.util.LinkedHashMap<>();
    private static final java.util.List<Process> servers = new java.util.ArrayList<>();
    private static Path python;

    @BeforeAll
    static void prepare() throws Exception {
        workspace = Files.createTempDirectory("mcp-acceptance-");
        python = Path.of("/usr/bin/python3").toRealPath();
        Path script =
                Path.of("../specs/005-expand-tool-capabilities/fixtures/mcp_test_server.py")
                        .toRealPath();
        var yaml = new StringBuilder("servers:\n");
        var json = JsonMapper.builder().build();
        for (String mode : List.of("stdio", "sse", "streamable-http")) {
            String id = mode.equals("streamable-http") ? "http" : mode;
            Path dir = Files.createDirectory(workspace.resolve(id));
            fixtures.put(id, dir);
            Files.writeString(dir.resolve("control.json"), "{}");
            List<String> args =
                    List.of(
                            script.toString(),
                            "--transport",
                            mode,
                            "--control",
                            dir.resolve("control.json").toString(),
                            "--stats",
                            dir.resolve("stats.json").toString(),
                            "--ready",
                            dir.resolve("ready.json").toString());
            yaml.append("  - name: ")
                    .append(id)
                    .append("\n    transport: ")
                    .append(mode)
                    .append('\n');
            if (mode.equals("stdio")) {
                yaml.append("    command: ")
                        .append(json.writeValueAsString(python.toString()))
                        .append("\n    args: ")
                        .append(json.writeValueAsString(args))
                        .append("\n    cwd: ")
                        .append(json.writeValueAsString(dir.toString()))
                        .append('\n');
            } else {
                var command = new java.util.ArrayList<String>();
                command.add(python.toString());
                command.addAll(args);
                var builder = new ProcessBuilder(command);
                builder.environment().clear();
                servers.add(builder.start());
                long deadline =
                        System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
                while (!Files.exists(dir.resolve("ready.json")) && System.nanoTime() < deadline) {
                    Thread.sleep(5);
                }
                int port =
                        json.readTree(Files.readString(dir.resolve("ready.json")))
                                .path("port")
                                .asInt();
                yaml.append("    url: http://127.0.0.1:").append(port).append('\n');
                yaml.append(
                        mode.equals("sse") ? "    sse-endpoint: /sse\n" : "    endpoint: /mcp\n");
            }
            Path agent = Files.createDirectories(workspace.resolve("agents/" + id));
            Files.writeString(
                    agent.resolve("AGENT.md"),
                    "---\nname: "
                            + id
                            + "\nprovider:\n  model: test-model\ntools: [mcp_"
                            + id
                            + "__echo]\nmcp_servers: ["
                            + id
                            + "]\n---\n测试工具。\n");
        }
        Files.writeString(workspace.resolve("mcp_servers.yaml"), yaml.toString());
        Path denied = Files.createDirectories(workspace.resolve("agents/denied"));
        Files.writeString(
                denied.resolve("AGENT.md"),
                "---\nname: denied\nprovider:\n  model: test-model\ntools: []\n---\n无权限。\n");
        model = LocalModelServer.start();
    }

    @AfterAll
    static void stop() {
        if (model != null) {
            model.close();
        }
        for (Process server : servers) {
            server.destroyForcibly();
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
        registry.add("agentos.tools.shell.commands[0].executable", () -> python.toString());
        registry.add("agentos.tools.shell.commands[0].allowed-cwds[0]", () -> workspace.toString());
    }

    @Test
    void allTransportsUseOneGovernedManagerAndOriginalDatabaseRow(
            org.springframework.context.ApplicationContext context) throws Exception {
        var service = context.getBean(AgentService.class);
        var catalog = context.getBean(com.agentos.core.tool.ToolCatalog.class);
        var loader = context.getBean(com.agentos.core.agent.AgentLoader.class);
        var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        var json = JsonMapper.builder().build();
        for (String id : List.of("stdio", "sse", "http")) {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                try {
                    if (!catalog.resolve(loader.load(id).profile()).modelCallbacks().isEmpty()) {
                        break;
                    }
                } catch (RuntimeException notYetDiscovered) {
                    /* 背景发现尚未发布，仍受本测试等待上限。 */
                }
                Thread.sleep(10);
            }
            assertThat(catalog.resolve(loader.load(id).profile()).modelCallbacks()).hasSize(1);
            for (String mode : List.of("allowed", "denied", "begin-failure", "complete-failure")) {
                String callId = id + "-" + mode;
                Path stats = fixtures.get(id).resolve("stats.json");
                int before = json.readTree(Files.readString(stats)).path("call").asInt();
                model.reset(
                        response(callId, "mcp_" + id + "__echo"),
                        LocalModelServer.textResponse("完成"));
                var result =
                        service.process(
                                new ProcessRequest(
                                        "test",
                                        java.util.UUID.randomUUID().toString(),
                                        mode.equals("denied") ? "denied" : id,
                                        "MCP测试",
                                        "test"));
                boolean uncertain = mode.endsWith("failure");
                assertThat(result.status())
                        .isEqualTo(uncertain ? TurnStatus.FAILED : TurnStatus.SUCCEEDED);
                assertThat(json.readTree(Files.readString(stats)).path("call").asInt() - before)
                        .isEqualTo(
                                mode.equals("allowed") || mode.equals("complete-failure") ? 1 : 0);
                var rows =
                        jdbc.queryForList(
                                "select tool_call_id,tool_source,attempts from tool_invocations where turn_id=?",
                                result.turnId());
                if (mode.equals("begin-failure")) {
                    assertThat(rows).isEmpty();
                } else {
                    assertThat(rows).hasSize(1);
                    assertThat(rows.getFirst())
                            .containsEntry("tool_call_id", callId)
                            .containsEntry("tool_source", "mcp:" + id)
                            .containsEntry("attempts", mode.equals("denied") ? 0 : 1);
                }
                assertThat(model.calls()).isEqualTo(uncertain ? 1 : 2);
                if (!uncertain) {
                    assertThat(model.requests().getLast()).contains(callId);
                }
            }
        }
    }

    private String response(String id, String tool) {
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
                                                                                "{}")))),
                                                "finish_reason",
                                                "tool_calls"))));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean
        ToolInvocationRecorder mcpRecorder(
                ToolInvocationRepository repository,
                Redactor redactor,
                AuditTransactions transactions,
                TurnBudgetRegistry budgets,
                AuditProperties properties) {
            var delegate =
                    new JpaToolInvocationRecorder(
                            repository, redactor, transactions, budgets, properties);
            return new ToolInvocationRecorder() {
                public ToolInvocationRecord begin(ToolInvocationRecord record) {
                    if (record.toolCallId().endsWith("begin-failure")) {
                        throw new AuditWriteException("测试begin未确认", false);
                    }
                    return delegate.begin(record);
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
}
