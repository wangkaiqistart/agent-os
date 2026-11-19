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

/** 文件工具通过真实Manager和PostgreSQL，核对真实磁盘状态及原调用回填。 */
@Tag("db")
@ExtendWith(BootDatabaseTestEnvironment.class)
@Testcontainers
@SpringBootTest(
        classes = {
            AgentOSApplication.class,
            FileToolsAcceptanceIntegrationTest.Configuration.class
        },
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.main.web-application-type=none",
            "spring.config.import=",
            "spring.ai.tools.limits.max-calls-per-tool-default=2"
        })
class FileToolsAcceptanceIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    DockerImageName.parse("pgvector/pgvector:pg17")
                            .asCompatibleSubstituteFor("postgres"));

    private static Path workspace;
    private static LocalModelServer model;

    @BeforeAll
    static void prepare() throws Exception {
        workspace = Files.createTempDirectory("file-acceptance-");
        for (String name : List.of("allowed", "denied")) {
            var directory = Files.createDirectories(workspace.resolve("agents").resolve(name));
            Files.writeString(
                    directory.resolve("AGENT.md"),
                    "---\nname: "
                            + name
                            + "\nprovider:\n  model: test-model\ntools: "
                            + (name.equals("allowed") ? "[read_file, write_file, list_dir]" : "[]")
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
        registry.add("agentos.tools.file.read-allowed-paths[0]", () -> workspace.toString());
        registry.add("agentos.tools.file.write-allowed-paths[0]", () -> workspace.toString());
    }

    @Test
    void filesAreAuthorizedAuditedAndFedBackWithOriginalCallId(
            org.springframework.context.ApplicationContext context) throws Exception {
        var service = context.getBean(AgentService.class);
        var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        for (String mode :
                List.of(
                        "write",
                        "read",
                        "list",
                        "denied",
                        "invalid",
                        "begin-failure",
                        "complete-failure")) {
            String tool =
                    mode.equals("read")
                            ? "read_file"
                            : mode.equals("list") ? "list_dir" : "write_file";
            String target = mode.equals("read") ? "write.txt" : mode + ".txt";
            String arguments =
                    mode.equals("invalid")
                            ? "{}"
                            : JsonMapper.builder()
                                    .build()
                                    .writeValueAsString(
                                            tool.equals("write_file")
                                                    ? Map.of("path", target, "content", "文件验收")
                                                    : Map.of(
                                                            "path",
                                                            mode.equals("list") ? "." : target));
            model.reset(response(mode, tool, arguments), LocalModelServer.textResponse("完成"));
            var result =
                    service.process(
                            new ProcessRequest(
                                    "test",
                                    java.util.UUID.randomUUID().toString(),
                                    mode.equals("denied") ? "denied" : "allowed",
                                    "文件工具测试",
                                    "test"));
            boolean uncertain = mode.endsWith("failure");
            assertThat(result.status())
                    .as(mode)
                    .isEqualTo(uncertain ? TurnStatus.FAILED : TurnStatus.SUCCEEDED);
            assertThat(result.persistenceStatus())
                    .isEqualTo(
                            uncertain
                                    ? PersistenceStatus.UNCONFIRMED
                                    : PersistenceStatus.CONFIRMED);
            if (tool.equals("write_file")) {
                boolean committed = mode.equals("write") || mode.equals("complete-failure");
                assertThat(Files.exists(workspace.resolve(target))).as(mode).isEqualTo(committed);
                if (committed) {
                    assertThat(Files.readString(workspace.resolve(target))).isEqualTo("文件验收");
                }
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
                        .containsEntry(
                                "attempts",
                                mode.equals("denied") || mode.equals("invalid") ? 0 : 1);
                if (mode.equals("complete-failure")) {
                    assertThat(rows.getFirst()).containsEntry("status", "unconfirmed");
                }
            }
            assertThat(model.calls()).isEqualTo(uncertain ? 1 : 2);
            if (!uncertain) {
                assertThat(model.requests().getLast()).contains(mode);
            }
            if (mode.equals("read")) {
                assertThat(model.requests().getLast()).contains("文件验收");
            }
            if (!mode.equals("denied")) {
                assertThat(model.requests().getFirst())
                        .contains("read_file", "write_file", "list_dir");
            }
        }
    }

    @Test
    void cancellationLeaseLossAndAttemptLimitsPreventNewFileActions(
            org.springframework.context.ApplicationContext context) {
        var service = context.getBean(AgentService.class);
        var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        for (String mode : List.of("cancel-before-action", "lease-before-action")) {
            String target = mode + ".txt";
            model.reset(
                    response(
                            mode,
                            "write_file",
                            JsonMapper.builder()
                                    .build()
                                    .writeValueAsString(
                                            Map.of("path", target, "content", "forbidden"))));
            var result =
                    service.process(
                            new ProcessRequest(
                                    "test",
                                    java.util.UUID.randomUUID().toString(),
                                    "allowed",
                                    "中断测试",
                                    "test"));
            assertThat(result.status()).isEqualTo(TurnStatus.INTERRUPTED);
            assertThat(Files.exists(workspace.resolve(target))).isFalse();
            assertThat(model.calls()).isEqualTo(1);
            var rows =
                    jdbc.queryForList(
                            "select tool_call_id,tool_source,attempts from tool_invocations where turn_id=?",
                            result.turnId());
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst())
                    .containsEntry("tool_call_id", mode)
                    .containsEntry("tool_source", "builtin")
                    .containsEntry("attempts", 0);
        }
        var json = JsonMapper.builder().build();
        var calls = new java.util.ArrayList<Object>();
        for (int i = 0; i < 3; i++) {
            calls.add(
                    Map.of(
                            "id",
                            "limit-" + i,
                            "type",
                            "function",
                            "function",
                            Map.of(
                                    "name",
                                    "write_file",
                                    "arguments",
                                    json.writeValueAsString(
                                            Map.of("path", "limit-" + i, "content", "once")))));
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
                                "上限测试",
                                "test"));
        assertThat(result.status()).isEqualTo(TurnStatus.SUCCEEDED);
        assertThat(Files.exists(workspace.resolve("limit-0"))).isTrue();
        assertThat(Files.exists(workspace.resolve("limit-1"))).isTrue();
        assertThat(Files.exists(workspace.resolve("limit-2"))).isFalse();
        assertThat(
                        jdbc.queryForMap(
                                "select tool_source,status,attempts from tool_invocations where turn_id=? and tool_call_id='limit-2'",
                                result.turnId()))
                .containsEntry("tool_source", "builtin")
                .containsEntry("status", "limit_skipped")
                .containsEntry("attempts", 0);
        assertThat(model.requests().getLast()).contains("limit-0", "limit-1", "limit-2");
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
        ToolInvocationRecorder fileRecorder(
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
