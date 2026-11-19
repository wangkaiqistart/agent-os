package com.agentos.boot;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;
import com.agentos.core.tool.ToolCatalogQuery;
import com.agentos.core.turn.TurnSink;
import com.agentos.core.turn.TurnStatus;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 秘密与新交互能力键贯穿事件/历史/日志/审计/查询与MCP边界。 */
@Import(ToolCapabilitiesPrivacyIntegrationTest.EarlyPrivacyConfiguration.class)
class ToolCapabilitiesPrivacyIntegrationTest extends ToolCapabilitiesFixture {
    static final String SHELL_LITERAL = "shell-deployment-private";
    static final String SHELL_REFERENCE = "convergence-reference-private";

    static final String MCP_TOKEN = "phase14-mcp-token-private";
    static final String MCP_PART = "phase14-mcp-part-private";
    static final String MCP_COMPOSITE = "prefix-" + MCP_TOKEN + "-" + MCP_PART + "-suffix";
    static final String[] CONFIGURED_SECRETS = {
        SHELL_LITERAL, SHELL_REFERENCE, MCP_TOKEN, MCP_PART, MCP_COMPOSITE
    };

    @org.junit.jupiter.api.BeforeAll
    static void prepareCompositeMcpSecrets() throws Exception {
        Files.writeString(
                workspace.resolve("mcp_servers.yaml"),
                Files.readString(workspace.resolve("mcp_servers.yaml"))
                        + "    env:\n      COMPOSITE: prefix-${PHASE14_MCP_TOKEN}-${PHASE14_MCP_PART}-suffix\n"
                        + "  - name: private-header\n    transport: sse\n    url: http://127.0.0.1:9\n"
                        + "    headers:\n      Authorization: Bearer ${PHASE14_MCP_TOKEN}\n");
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.BEFORE_METHOD)
    void compositeMcpSecretsAreSafeBeforeAnyRemoteCall(ApplicationContext context)
            throws Exception {
        var jdbc = context.getBean(JdbcTemplate.class);
        long before =
                jdbc.queryForObject(
                        "select count(*) from tool_invocations where tool_source like 'mcp:%'",
                        Long.class);
        Files.writeString(
                workspace.resolve("mcp-private.txt"),
                String.join(" ", CONFIGURED_SECRETS) + " Bearer " + MCP_TOKEN);
        assertSafeEarlyInvocation(context, "read_file", Map.of("path", "mcp-private.txt"));
        assertSafeEarlyInvocation(context, "early_privacy", Map.of());
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from tool_invocations where tool_source like 'mcp:%'",
                                Long.class))
                .isEqualTo(before);
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.BEFORE_METHOD)
    void remoteResultAndFirstArgumentsMaskEachConfiguredMcpComponent(ApplicationContext context)
            throws Exception {
        awaitRemote(context);
        assertSafeEarlyInvocation(
                context,
                "mcp_fixture__echo",
                Map.of("value", String.join(" ", CONFIGURED_SECRETS)));
    }

    @DynamicPropertySource
    static void referenceSecret(DynamicPropertyRegistry properties) {
        properties.add("CONVERGENCE_SHELL_ENV_VALUE", () -> SHELL_REFERENCE);
        properties.add(
                "agentos.tools.shell.commands[0].env.REFERENCE_TOKEN",
                () -> "${CONVERGENCE_SHELL_ENV_VALUE}");
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.BEFORE_METHOD)
    void firstShellArgumentsAreSafeBeforeBeginAndComplete(ApplicationContext context)
            throws Exception {
        assertSafeEarlyInvocation(
                context,
                "shell",
                Map.of(
                        "executable", python.toString(),
                        "args",
                                List.of(
                                        "-c",
                                        "import sys; print(' '.join(sys.argv[1:]))",
                                        SHELL_LITERAL,
                                        SHELL_REFERENCE)));
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.BEFORE_METHOD)
    void filesPluginsAndDirectoryAreSafeWithoutExecutingShell(ApplicationContext context)
            throws Exception {
        var jdbc = context.getBean(JdbcTemplate.class);
        long before =
                jdbc.queryForObject(
                        "select count(*) from tool_invocations where tool_name='shell'",
                        Long.class);
        Files.writeString(
                workspace.resolve("early-private.txt"), SHELL_LITERAL + " " + SHELL_REFERENCE);
        assertSafeEarlyInvocation(context, "read_file", Map.of("path", "early-private.txt"));
        assertSafeEarlyInvocation(context, "early_privacy", Map.of());
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from tool_invocations where tool_name='shell'",
                                Long.class))
                .isEqualTo(before);
    }

    private void assertSafeEarlyInvocation(
            ApplicationContext context, String tool, Map<String, Object> args) throws Exception {
        var logs = new ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        logs.start();
        var logger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        logger.addAppender(logs);
        try {
            var profile = Files.createDirectories(workspace.resolve("agents/early-privacy"));
            Files.writeString(
                    profile.resolve("AGENT.md"),
                    "---\nname: early-privacy\nprovider:\n  model: test-model\ntools: [shell, read_file, early_privacy, mcp_fixture__echo]\nmcp_servers: [fixture]\n---\n使用授权工具。\n");
            String callId = "early-" + UUID.randomUUID();
            model.reset(
                    response(callId, tool, args),
                    com.agentos.boot.support.LocalModelServer.textResponse("完成"));
            var emitted = new ArrayList<String>();
            var result =
                    context.getBean(com.agentos.core.turn.AgentService.class)
                            .process(
                                    new com.agentos.core.turn.ProcessRequest(
                                            "test",
                                            UUID.randomUUID().toString(),
                                            "early-privacy",
                                            "测试配置秘密",
                                            "test"),
                                    new TurnSink() {
                                        public void onToolCall(
                                                String id, String name, String input) {
                                            emitted.add(input);
                                        }

                                        public void onToolResult(
                                                String id, String name, String output) {
                                            emitted.add(output);
                                        }
                                    });
            assertThat(result.status()).isEqualTo(TurnStatus.SUCCEEDED);
            assertThat(result.text()).doesNotContain(CONFIGURED_SECRETS);
            var jdbc = context.getBean(JdbcTemplate.class);
            var rows =
                    jdbc.queryForList(
                            "select tool_call_id,status,input_json::text,result_text,error_message from tool_invocations where turn_id=?",
                            result.turnId());
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst())
                    .containsEntry("tool_call_id", callId)
                    .containsEntry("status", "success");
            assertThat(rows.toString()).contains("***").doesNotContain(CONFIGURED_SECRETS);
            assertThat(
                            jdbc.queryForList(
                                            "select content,tool_calls::text,tool_responses::text from session_messages where turn_id=?",
                                            result.turnId())
                                    .toString())
                    .contains("***")
                    .doesNotContain(CONFIGURED_SECRETS);
            assertThat(model.requests().getLast())
                    .contains("***")
                    .doesNotContain(CONFIGURED_SECRETS);
            assertThat(emitted.toString()).contains("***").doesNotContain(CONFIGURED_SECRETS);
            assertThat(
                            logs.list.stream()
                                    .map(
                                            ch.qos.logback.classic.spi.ILoggingEvent
                                                    ::getFormattedMessage)
                                    .toList()
                                    .toString())
                    .doesNotContain(CONFIGURED_SECRETS);
            var directory = context.getBean(ToolCatalogQuery.class).snapshot(null);
            assertThat(JSON.writeValueAsString(directory)).doesNotContain(CONFIGURED_SECRETS);
            assertThat(directory.tools())
                    .anySatisfy(item -> {
                        assertThat(item.name()).isEqualTo("early_privacy");
                        assertThat(item.description()).contains("***");
                    });
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class EarlyPrivacyConfiguration {
        @Bean
        @org.springframework.context.annotation.Primary
        com.agentos.tool.mcp.McpConfigurationLoader privacyMcpConfigurationLoader() {
            return new com.agentos.tool.mcp.McpConfigurationLoader(
                    Map.of("PHASE14_MCP_TOKEN", MCP_TOKEN, "PHASE14_MCP_PART", MCP_PART)::get);
        }

        @Bean
        @org.springframework.context.annotation.Primary
        com.agentos.core.audit.ToolInvocationRecorder privacyRecorder(
                @org.springframework.beans.factory.annotation.Qualifier("matrixRecorder")
                        com.agentos.core.audit.ToolInvocationRecorder delegate) {
            return new com.agentos.core.audit.ToolInvocationRecorder() {
                public com.agentos.core.audit.ToolInvocationRecord begin(
                        com.agentos.core.audit.ToolInvocationRecord record) {
                    assertThat(record.inputJson()).doesNotContain(CONFIGURED_SECRETS);
                    return delegate.begin(record);
                }

                public void complete(com.agentos.core.audit.ToolInvocationRecord record) {
                    assertThat(record.toString()).doesNotContain(CONFIGURED_SECRETS);
                    delegate.complete(record);
                }

                public void skipped(com.agentos.core.audit.ToolInvocationRecord record) {
                    delegate.skipped(record);
                }
            };
        }

        @Bean
        EarlyPrivacyTools earlyPrivacyTools() {
            return new EarlyPrivacyTools();
        }
    }

    static class EarlyPrivacyTools {
        @org.springframework.ai.tool.annotation.Tool(
                name = "early_privacy",
                description =
                        "回显已配置值 "
                                + SHELL_LITERAL
                                + " "
                                + SHELL_REFERENCE
                                + " "
                                + MCP_TOKEN
                                + " "
                                + MCP_PART)
        public String echo() {
            return String.join(" ", CONFIGURED_SECRETS);
        }
    }

    @Test
    void secretsAndCapabilitiesNeverEscapeSafeSurfaces(ApplicationContext context)
            throws Exception {
        awaitRemote(context);
        var logs = new ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        logs.start();
        var logger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        logger.addAppender(logs);
        try {
            Files.writeString(workspace.resolve("sample.txt"), "target " + SECRET);
            var jdbc = context.getBean(JdbcTemplate.class);
            var emitted = new ArrayList<String>();
            TurnSink sink =
                    new TurnSink() {
                        public void onToolCall(String id, String name, String args) {
                            emitted.add(args);
                        }

                        public void onToolResult(String id, String name, String result) {
                            emitted.add(result);
                        }
                    };
            for (String tool :
                    List.of(
                            "http_get",
                            "read_file",
                            "web_search",
                            "shell",
                            "ask_user",
                            "mcp_fixture__echo")) {
                var args = new LinkedHashMap<>(arguments(tool));
                if (tool.equals("shell")) {
                    args.put(
                            "args",
                            List.of(
                                    "-c",
                                    "import os; print(os.environ.get('HOME')); print(os.environ.get('DEPLOYMENT_TOKEN')); print('"
                                            + SECRET
                                            + "')"));
                }
                if (tool.startsWith("mcp_")) {
                    Files.writeString(
                            control, "{\"inspectEnv\":[\"HOME\",\"AGENTOS_LLM_API_KEY\"]}");
                    args.put("value", SECRET);
                }
                if (tool.equals("ask_user")) {
                    args.put("question", SECRET + " 问题");
                }
                var result = run(context, tool, "privacy", args, sink);
                assertThat(result.status()).isEqualTo(TurnStatus.SUCCEEDED);
                var rows =
                        jdbc.queryForList(
                                "select input_json::text,result_text,error_message from tool_invocations where turn_id=?",
                                result.turnId());
                assertThat(rows).hasSize(1);
                assertThat(rows.toString()).doesNotContain(SECRET, "shell-deployment-private");
                assertThat(model.requests().getLast())
                        .doesNotContain(SECRET, "shell-deployment-private");
                var history =
                        jdbc.queryForList(
                                "select content,tool_calls::text,tool_responses::text from session_messages where turn_id=?",
                                result.turnId());
                assertThat(history).isNotEmpty();
                assertThat(history.toString()).doesNotContain(SECRET, "shell-deployment-private");
                if (tool.equals("shell")) {
                    assertThat(model.requests().getLast()).contains("None");
                }
            }
            var captured = JSON.readTree(Files.readString(stats)).path("calls").get(0);
            assertThat(captured.path("meta").isNull()).isTrue();
            assertThat(captured.path("params").propertyNames())
                    .containsExactlyInAnyOrder("name", "arguments", "_meta");
            assertThat(captured.path("params").path("_meta").size()).isZero();
            assertThat(captured.toString())
                    .doesNotContain(
                            "TurnRuntimeCapabilities",
                            "turnId",
                            "lease",
                            "budget",
                            workspace.toString());
            assertThat(model.requests().getLast()).contains("null");
            assertThat(emitted.toString()).doesNotContain(SECRET, "shell-deployment-private");
            assertThat(
                            JSON.writeValueAsString(
                                    context.getBean(ToolCatalogQuery.class).snapshot(null)))
                    .doesNotContain(
                            SECRET, workspace.toString(), "127.0.0.1", "inputSchema", "callback");
            assertThat(
                            logs.list.stream()
                                    .map(
                                            ch.qos.logback.classic.spi.ILoggingEvent
                                                    ::getFormattedMessage)
                                    .toList()
                                    .toString())
                    .doesNotContain(SECRET, "问题", "DEMO_BUSINESS_VALUE");
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }
}
