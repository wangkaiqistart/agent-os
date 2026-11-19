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

/** Brave协议替身经过真实Manager和PostgreSQL，验证实际请求及隐私。 */
@Tag("db")
@ExtendWith(BootDatabaseTestEnvironment.class)
@Testcontainers
@SpringBootTest(
        classes = {
            AgentOSApplication.class,
            WebSearchAcceptanceIntegrationTest.Configuration.class
        },
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.main.web-application-type=none",
            "spring.config.import=",
            "spring.ai.tools.limits.max-calls-per-tool-default=2"
        })
class WebSearchAcceptanceIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    DockerImageName.parse("pgvector/pgvector:pg17")
                            .asCompatibleSubstituteFor("postgres"));

    private static Path workspace;
    private static LocalModelServer model;
    private static com.sun.net.httpserver.HttpServer searchServer;
    private static final java.util.concurrent.atomic.AtomicInteger requests =
            new java.util.concurrent.atomic.AtomicInteger();

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
                            + (name.equals("allowed") ? "[web_search]" : "[]")
                            + "\n---\n测试插件。\n");
        }
        model = LocalModelServer.start();
        searchServer =
                com.sun.net.httpserver.HttpServer.create(
                        new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        searchServer.createContext(
                "/search",
                exchange -> {
                    requests.incrementAndGet();
                    assertThat(exchange.getRequestHeaders().getFirst("X-Subscription-Token"))
                            .isEqualTo("search-secret-sentinel");
                    byte[] bytes =
                            "{\"web\":{\"results\":[{\"title\":\"受控结果 search-secret-sentinel\",\"url\":\"https://example.org/item\",\"description\":\"摘要\"}]}}"
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        searchServer.start();
    }

    @AfterAll
    static void stop() {
        if (searchServer != null) {
            searchServer.stop(0);
        }
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
        registry.add("agentos.tools.search.api-key", () -> "search-secret-sentinel");
        registry.add(
                "agentos.tools.search.endpoint",
                () -> "http://127.0.0.1:" + searchServer.getAddress().getPort() + "/search");
        registry.add("agentos.tools.http.allowed-domains[0]", () -> "127.0.0.1");
    }

    private static final java.util.concurrent.atomic.AtomicInteger accesses =
            new java.util.concurrent.atomic.AtomicInteger();

    @Test
    void searchUsesOriginalAuditAndBackfillAndNeverCallsAfterRejection(
            org.springframework.context.ApplicationContext context) {
        var service = context.getBean(AgentService.class);
        var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        for (String mode :
                List.of(
                        "search",
                        "denied",
                        "invalid",
                        "begin-failure",
                        "complete-failure",
                        "cancel-before-action",
                        "lease-before-action")) {
            requests.set(0);
            model.reset(
                    response(
                            mode,
                            "web_search",
                            mode.equals("invalid") ? "{}" : "{\"query\":\"测试\"}"),
                    LocalModelServer.textResponse("完成"));
            var result =
                    service.process(
                            new ProcessRequest(
                                    "test",
                                    java.util.UUID.randomUUID().toString(),
                                    mode.equals("denied") ? "denied" : "allowed",
                                    "测试搜索",
                                    "test"));
            boolean acted = List.of("search", "complete-failure").contains(mode);
            assertThat(requests).as(mode).hasValue(acted ? 1 : 0);
            var rows =
                    jdbc.queryForList(
                            "select tool_call_id,tool_source,input_json::text,result_text,attempts from tool_invocations where turn_id=?",
                            result.turnId());
            if (mode.equals("begin-failure")) {
                assertThat(rows).isEmpty();
            } else {
                assertThat(rows).hasSize(1);
                assertThat(rows.getFirst())
                        .containsEntry("tool_call_id", mode)
                        .containsEntry("tool_source", "builtin")
                        .containsEntry("attempts", acted ? 1 : 0);
                assertThat(rows.toString()).doesNotContain("search-secret-sentinel");
            }
            if (!mode.endsWith("failure") && !mode.endsWith("before-action")) {
                assertThat(model.calls()).isEqualTo(2);
                assertThat(model.requests().getLast())
                        .contains(mode)
                        .doesNotContain("search-secret-sentinel");
            }
            if (mode.equals("search")) {
                assertThat(model.requests().getLast()).contains("受控结果", "items");
            }
        }
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
        ToolInvocationRecorder editRecorder(
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
