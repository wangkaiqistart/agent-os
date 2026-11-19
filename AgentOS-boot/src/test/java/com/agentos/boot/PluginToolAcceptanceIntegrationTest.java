package com.agentos.boot;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentos.boot.support.LocalModelServer;
import com.agentos.core.audit.AuditWriteException;
import com.agentos.core.audit.ToolInvocationRecord;
import com.agentos.core.audit.ToolInvocationRecorder;
import com.agentos.core.config.AuditProperties;
import com.agentos.core.tool.Redactor;
import com.agentos.core.tool.TransientToolException;
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
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.definition.ToolDefinition;
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

/** 企业Bean与显式callback通过真实Manager和PostgreSQL，循环无需插件专属分支。 */
@Tag("db")
@ExtendWith(BootDatabaseTestEnvironment.class)
@Testcontainers
@SpringBootTest(
        classes = {
            AgentOSApplication.class,
            PluginToolAcceptanceIntegrationTest.Configuration.class
        },
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.main.web-application-type=none", "spring.config.import="})
class PluginToolAcceptanceIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    DockerImageName.parse("pgvector/pgvector:pg17")
                            .asCompatibleSubstituteFor("postgres"));

    private static Path workspace;
    private static LocalModelServer model;
    private static final AtomicInteger queries = new AtomicInteger();
    private static final AtomicInteger mutations = new AtomicInteger();

    @BeforeAll
    static void prepare() throws Exception {
        workspace = Files.createTempDirectory("plugin-acceptance-");
        for (String name : List.of("allowed", "denied")) {
            var directory = Files.createDirectories(workspace.resolve("agents").resolve(name));
            Files.writeString(
                    directory.resolve("AGENT.md"),
                    "---\nname: "
                            + name
                            + "\nprovider:\n  model: test-model\ntools: "
                            + (name.equals("allowed") ? "[lookup, mutate]" : "[]")
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
    }

    @Test
    void pluginAuthorizationAuditAndRetryRemainOnOriginalCall(
            org.springframework.context.ApplicationContext context) {
        var service = context.getBean(AgentService.class);
        var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        for (String mode :
                List.of("lookup", "mutate", "denied", "begin-failure", "complete-failure")) {
            queries.set(0);
            mutations.set(0);
            String tool = mode.equals("lookup") ? "lookup" : "mutate";
            model.reset(response(mode, tool), LocalModelServer.textResponse("完成"));
            var result =
                    service.process(
                            new ProcessRequest(
                                    "test",
                                    java.util.UUID.randomUUID().toString(),
                                    mode.equals("denied") ? "denied" : "allowed",
                                    "测试插件",
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
            assertThat(mutations)
                    .hasValue(mode.equals("mutate") || mode.equals("complete-failure") ? 1 : 0);
            assertThat(queries).hasValue(mode.equals("lookup") ? 2 : 0);
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
                        .containsEntry(
                                "tool_source",
                                mode.equals("lookup") ? "java:queryPlugin" : "java:mutationPlugin")
                        .containsEntry(
                                "attempts",
                                mode.equals("lookup") ? 2 : mode.equals("denied") ? 0 : 1);
            }
            assertThat(model.calls()).isEqualTo(uncertain ? 1 : 2);
            if (!uncertain) {
                assertThat(model.requests().getLast()).contains(mode);
            }
            if (mode.equals("lookup")) {
                assertThat(model.requests().getLast()).contains("fixture-lookup");
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

    static class QueryPlugin {
        @Tool(name = "lookup", description = "测试查询插件")
        @com.agentos.tool.annotation.ToolPolicy(idempotent = true, timeout = "2s")
        public String lookup() {
            if (queries.incrementAndGet() == 1) {
                throw new TransientToolException(false);
            }
            return "fixture-lookup";
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean
        QueryPlugin queryPlugin() {
            return new QueryPlugin();
        }

        @Bean
        ToolCallback mutationPlugin() {
            return new ToolCallback() {
                public ToolDefinition getToolDefinition() {
                    return ToolDefinition.builder()
                            .name("mutate")
                            .description("测试副作用插件")
                            .inputSchema("{\"type\":\"object\"}")
                            .build();
                }

                public String call(String input) {
                    mutations.incrementAndGet();
                    return "fixture-mutation";
                }
            };
        }

        @Bean
        ToolInvocationRecorder pluginRecorder(
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
                    if (record.toolCallId().equals("begin-failure")) {
                        throw new AuditWriteException("测试begin未确认", false);
                    }
                    return delegate.begin(record);
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
