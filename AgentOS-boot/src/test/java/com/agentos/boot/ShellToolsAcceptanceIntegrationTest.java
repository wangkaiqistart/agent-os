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

/** 进程通过真实Manager和PostgreSQL，核对实际动作次数与拒绝零启动。 */
@Tag("db")
@ExtendWith(BootDatabaseTestEnvironment.class)
@Testcontainers
@SpringBootTest(
        classes = {
            AgentOSApplication.class,
            ShellToolsAcceptanceIntegrationTest.Configuration.class
        },
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.main.web-application-type=none",
            "spring.config.import=",
            "spring.ai.tools.limits.max-calls-per-tool-default=2"
        })
class ShellToolsAcceptanceIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    DockerImageName.parse("pgvector/pgvector:pg17")
                            .asCompatibleSubstituteFor("postgres"));

    private static Path workspace;
    private static LocalModelServer model;

    @BeforeAll
    static void prepare() throws Exception {
        workspace = Files.createTempDirectory("shell-acceptance-");
        for (String name : List.of("allowed", "denied")) {
            var directory = Files.createDirectories(workspace.resolve("agents").resolve(name));
            Files.writeString(
                    directory.resolve("AGENT.md"),
                    "---\nname: "
                            + name
                            + "\nprovider:\n  model: test-model\ntools: "
                            + (name.equals("allowed") ? "[shell]" : "[]")
                            + "\n---\n测试插件。\n");
        }
        model = LocalModelServer.start();
    }

    @AfterAll
    static void stop() {
        if (model != null) {
            model.close();
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
        registry.add(
                "agentos.tools.shell.commands[0].executable", () -> shellExecutable().toString());
        registry.add("agentos.tools.shell.commands[0].allowed-cwds[0]", () -> workspace.toString());
    }

    private static Path shellExecutable() {
        try {
            return Path.of("/bin/sh").toRealPath();
        } catch (java.io.IOException unavailable) {
            throw new IllegalStateException("测试需要本地解释器");
        }
    }

    private String arguments(String target) {
        return JsonMapper.builder()
                .build()
                .writeValueAsString(
                        Map.of(
                                "executable",
                                shellExecutable().toString(),
                                "args",
                                List.of("-c", "printf x >> \"$1\"", "fixture", target)));
    }

    @Test
    void authorizedProcessExecutesOnceAndAuditFailuresNeverReplay(
            org.springframework.context.ApplicationContext context) throws Exception {
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
            Path target = workspace.resolve(mode + ".txt");
            model.reset(
                    response(
                            mode,
                            "shell",
                            mode.equals("invalid") ? "{}" : arguments(target.toString())),
                    LocalModelServer.textResponse("完成"));
            var result =
                    service.process(
                            new ProcessRequest(
                                    "test",
                                    java.util.UUID.randomUUID().toString(),
                                    mode.equals("denied") ? "denied" : "allowed",
                                    "执行测试",
                                    "test"));
            boolean uncertain = mode.endsWith("failure");
            boolean interrupted = mode.endsWith("before-action");
            assertThat(result.status())
                    .as(mode)
                    .isEqualTo(
                            uncertain
                                    ? TurnStatus.FAILED
                                    : interrupted ? TurnStatus.INTERRUPTED : TurnStatus.SUCCEEDED);
            boolean executed = mode.equals("allowed") || mode.equals("complete-failure");
            assertThat(Files.exists(target)).as(mode).isEqualTo(executed);
            if (executed) {
                assertThat(Files.readString(target)).isEqualTo("x");
            }
            var rows =
                    jdbc.queryForList(
                            "select tool_call_id,tool_source,status,attempts from tool_invocations where turn_id=?",
                            result.turnId());
            if (mode.equals("begin-failure")) {
                assertThat(rows).isEmpty();
            } else {
                assertThat(rows).hasSize(1);
                assertThat(rows.getFirst())
                        .containsEntry("tool_call_id", mode)
                        .containsEntry("tool_source", "builtin")
                        .containsEntry("attempts", executed ? 1 : 0);
            }
            assertThat(model.calls()).isEqualTo(uncertain || interrupted ? 1 : 2);
            if (!uncertain && !interrupted) {
                assertThat(model.requests().getLast()).contains(mode);
            }
            if (mode.equals("allowed")) {
                assertThat(model.requests().getLast()).contains("exitCode", "cleanupConfirmed");
            }
        }
    }

    @Test
    void attemptLimitCountsActualProcessStartsAndPreservesEveryCallId(
            org.springframework.context.ApplicationContext context) throws Exception {
        var service = context.getBean(AgentService.class);
        var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        var json = JsonMapper.builder().build();
        var calls = new java.util.ArrayList<Object>();
        Path target = workspace.resolve("bounded-process-actions");
        for (int i = 0; i < 3; i++) {
            calls.add(
                    Map.of(
                            "id",
                            "process-limit-" + i,
                            "type",
                            "function",
                            "function",
                            Map.of("name", "shell", "arguments", arguments(target.toString()))));
        }
        model.reset(
                json.writeValueAsString(
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
                                                "tool_calls")))),
                LocalModelServer.textResponse("完成"));
        var result =
                service.process(
                        new ProcessRequest(
                                "test",
                                java.util.UUID.randomUUID().toString(),
                                "allowed",
                                "进程上限测试",
                                "test"));
        assertThat(result.status()).isEqualTo(TurnStatus.SUCCEEDED);
        assertThat(Files.readString(target)).isEqualTo("xx");
        assertThat(
                        jdbc.queryForMap(
                                "select tool_source,status,attempts from tool_invocations where turn_id=? and tool_call_id='process-limit-2'",
                                result.turnId()))
                .containsEntry("tool_source", "builtin")
                .containsEntry("status", "limit_skipped")
                .containsEntry("attempts", 0);
        assertThat(model.requests().getLast())
                .contains("process-limit-0", "process-limit-1", "process-limit-2");
    }

    private String response(String id, String tool, String arguments) {
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
                                                                                arguments)))),
                                                "finish_reason",
                                                "tool_calls"))));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean
        ToolInvocationRecorder shellRecorder(
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
