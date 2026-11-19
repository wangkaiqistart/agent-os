package com.agentos.boot.external;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentos.boot.AgentOSApplication;
import com.agentos.boot.BootDatabaseTestEnvironment;
import com.agentos.boot.support.LocalModelServer;
import com.agentos.core.turn.AgentService;
import com.agentos.core.turn.ProcessRequest;
import com.agentos.core.turn.TurnStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

/** profile和总开关之外，各外部服务还需显式配置；不读取个人.env。 */
@Tag("external-tools")
@Tag("db")
@EnabledIfEnvironmentVariable(named = "AGENTOS_EXTERNAL_TOOL_TESTS", matches = "true")
@ExtendWith(BootDatabaseTestEnvironment.class)
@Testcontainers
class ExternalToolCompatibilityTest {
    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    DockerImageName.parse("pgvector/pgvector:pg17")
                            .asCompatibleSubstituteFor("postgres"));

    @TempDir Path root;

    @Test
    @EnabledIfEnvironmentVariable(named = "AGENTOS_EXTERNAL_BRAVE_API_KEY", matches = ".+")
    void brave() throws Exception {
        verify("BRAVE");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "AGENTOS_EXTERNAL_MCP_STDIO_CONFIG", matches = ".+")
    void stdio() throws Exception {
        verify("STDIO");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "AGENTOS_EXTERNAL_MCP_SSE_CONFIG", matches = ".+")
    void sse() throws Exception {
        verify("SSE");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "AGENTOS_EXTERNAL_MCP_HTTP_CONFIG", matches = ".+")
    void streamableHttp() throws Exception {
        verify("HTTP");
    }

    private void verify(String kind) throws Exception {
        boolean brave = kind.equals("BRAVE");
        String prefix = "AGENTOS_EXTERNAL_MCP_" + kind;
        String tool = brave ? "web_search" : require(prefix + "_TOOL");
        String arguments =
                brave
                        ? "{\"query\":\"Spring AI official documentation\",\"limit\":1}"
                        : require(prefix + "_ARGUMENTS");
        var json = JsonMapper.builder().build();
        var values = new LinkedHashMap<String, Object>();
        String serverName = "";
        if (brave) {
            values.put("agentos.tools.search.api-key", require("AGENTOS_EXTERNAL_BRAVE_API_KEY"));
            values.put("agentos.tools.http.allowed-domains", "api.search.brave.com");
        } else {
            Files.copy(Path.of(require(prefix + "_CONFIG")), root.resolve("mcp_servers.yaml"));
            var loaded =
                    new com.agentos.tool.mcp.McpConfigurationLoader(System::getenv)
                            .load(root.resolve("mcp_servers.yaml"));
            assertThat(loaded.failures()).isEmpty();
            assertThat(loaded.servers()).hasSize(1);
            var server = loaded.servers().getFirst();
            serverName = server.name();
            assertThat(server.transport().name())
                    .isEqualTo(kind.equals("HTTP") ? "STREAMABLE_HTTP" : kind);
            if (server.url() != null) {
                values.put("agentos.tools.http.allowed-domains", server.url().getHost());
            } else {
                values.put(
                        "agentos.tools.shell.commands[0].executable", server.command().toString());
                values.put(
                        "agentos.tools.shell.commands[0].allowed-cwds[0]", server.cwd().toString());
            }
        }
        Path agent = Files.createDirectories(root.resolve("agents/external"));
        Files.writeString(
                agent.resolve("AGENT.md"),
                "---\nname: external\nprovider:\n  model: test-model\ntools: ["
                        + tool
                        + "]\nmcp_servers: "
                        + (brave ? "[]" : "[" + serverName + "]")
                        + "\n---\n外部兼容性。\n");
        try (var model = LocalModelServer.start()) {
            model.reset(
                    json.writeValueAsString(
                            Map.of(
                                    "id",
                                    "external",
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
                                                                            "external-call",
                                                                            "type",
                                                                            "function",
                                                                            "function",
                                                                            Map.of(
                                                                                    "name",
                                                                                    tool,
                                                                                    "arguments",
                                                                                    arguments)))),
                                                    "finish_reason",
                                                    "tool_calls")))),
                    LocalModelServer.textResponse("完成"));
            values.put("spring.config.import", "");
            values.put("agentos.workspace.root", root.toString());
            values.put("spring.datasource.url", POSTGRES.getJdbcUrl());
            values.put("spring.datasource.username", POSTGRES.getUsername());
            values.put("spring.datasource.password", POSTGRES.getPassword());
            values.put("agentos.providers.primary.base-url", model.baseUrl() + "/v1");
            values.put("agentos.providers.primary.api-key", "external-fixture-key");
            values.put("agentos.embedding.enabled", false);
            values.put("logging.file.name", root.resolve("external.log").toString());
            var environment = new StandardEnvironment();
            environment
                    .getPropertySources()
                    .addFirst(new MapPropertySource("external-isolated", values));
            try (var context =
                    new SpringApplicationBuilder(AgentOSApplication.class)
                            .environment(environment)
                            .web(WebApplicationType.NONE)
                            .registerShutdownHook(false)
                            .run()) {
                if (!brave) {
                    var catalog = context.getBean(com.agentos.core.tool.ToolCatalog.class);
                    var profile =
                            context.getBean(com.agentos.core.agent.AgentLoader.class)
                                    .load("external")
                                    .profile();
                    long end =
                            System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
                    while (catalog.resolve(profile).modelCallbacks().isEmpty()
                            && System.nanoTime() < end) {
                        Thread.sleep(20);
                    }
                    assertThat(catalog.resolve(profile).modelCallbacks()).hasSize(1);
                }
                var result =
                        context.getBean(AgentService.class)
                                .process(
                                        new ProcessRequest(
                                                "external",
                                                UUID.randomUUID().toString(),
                                                "external",
                                                "兼容性测试",
                                                "test"));
                assertThat(result.status()).isEqualTo(TurnStatus.SUCCEEDED);
                var rows =
                        context.getBean(JdbcTemplate.class)
                                .queryForList(
                                        "select status,tool_call_id from tool_invocations where turn_id=?",
                                        result.turnId());
                assertThat(rows).hasSize(1);
                assertThat(rows.getFirst())
                        .containsEntry("status", "success")
                        .containsEntry("tool_call_id", "external-call");
                assertThat(model.calls()).isEqualTo(2);
                assertThat(model.requests().getLast()).contains("external-call");
            }
        }
    }

    private static String require(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少显式外部测试变量：" + name);
        }
        return value;
    }
}
