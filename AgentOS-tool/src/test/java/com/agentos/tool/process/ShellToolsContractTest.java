package com.agentos.tool.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.core.tool.ToolExecutionScope;
import com.agentos.core.turn.ToolContextKeys;
import com.agentos.core.turn.TurnExecutionBudget;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;
import tools.jackson.databind.json.JsonMapper;

class ShellToolsContractTest {
    @TempDir Path workspace;

    static Path javaExecutable() throws Exception {
        return Path.of(System.getProperty("java.home"), "bin", "java").toRealPath();
    }

    static List<String> arguments(String mode, String... extra) {
        var args =
                new ArrayList<>(
                        List.of(
                                "-cp",
                                Path.of("target/test-classes").toAbsolutePath().toString()
                                        + java.io.File.pathSeparator
                                        + System.getProperty("java.class.path"),
                                ManagedProcessFixture.class.getName(),
                                mode));
        args.addAll(List.of(extra));
        return args;
    }

    static ToolExecutionScope scope(Duration timeout) {
        return new ToolExecutionScope(
                null, "shell", TurnExecutionBudget.start(Duration.ofSeconds(8)), timeout, () -> {});
    }

    static ToolContext context(ToolExecutionScope scope) {
        return new ToolContext(Map.of(ToolContextKeys.TOOL_SCOPE, scope));
    }

    ShellToolProperties properties() throws Exception {
        return ShellToolProperties.defaults(
                List.of(
                        new ShellToolProperties.Command(
                                javaExecutable(), List.of(workspace), Map.of())));
    }

    @Test
    void configuredSecretsAreRegisteredWithoutStartingOrRequiringMissingReferences()
            throws Exception {
        var redactor = new com.agentos.core.tool.Redactor(List::of);
        var properties =
                ShellToolProperties.defaults(
                        List.of(
                                new ShellToolProperties.Command(
                                        javaExecutable(),
                                        List.of(workspace),
                                        Map.of(
                                                "DECLARED",
                                                "early-command-private",
                                                "MISSING",
                                                "${AGENTOS_TEST_ABSENT_CONVERGENCE_VALUE}"))));
        var tools =
                new ShellTools(
                        workspace,
                        properties,
                        new com.agentos.tool.sandbox.WhitelistSandbox(List.of(), null, properties),
                        redactor);
        assertThat(redactor.redact("plain early-command-private")).isEqualTo("plain ***");
        try (var scope = scope(Duration.ofSeconds(4))) {
            assertThatThrownBy(() ->
                            tools.shell(
                                    javaExecutable().toString(),
                                    arguments("action", workspace.resolve("never").toString()),
                                    null,
                                    context(scope)))
                    .hasMessageContaining("UNCONFIGURED")
                    .hasMessageNotContaining("AGENTOS_TEST_ABSENT_CONVERGENCE_VALUE");
        }
        assertThat(Files.exists(workspace.resolve("never"))).isFalse();
    }

    @Test
    void argvIsLiteralAndNonzeroExitIsAResult() throws Exception {
        var tools = new ShellTools(workspace, properties());
        var json = JsonMapper.builder().build();
        try (var scope = scope(Duration.ofSeconds(4))) {
            var result =
                    json.readTree(
                            tools.shell(
                                    javaExecutable().toString(),
                                    arguments(
                                            "argv",
                                            "a b",
                                            "; touch forbidden",
                                            "$(touch forbidden)",
                                            "|",
                                            "*"),
                                    null,
                                    context(scope)));
            assertThat(result.path("exitCode").asInt()).isZero();
            assertThat(json.readTree(result.path("stdout").asText()))
                    .isEqualTo(
                            json.valueToTree(
                                    List.of(
                                            "a b",
                                            "; touch forbidden",
                                            "$(touch forbidden)",
                                            "|",
                                            "*")));
            assertThat(Files.exists(workspace.resolve("forbidden"))).isFalse();
            var failed =
                    json.readTree(
                            tools.shell(
                                    javaExecutable().toString(),
                                    arguments("exit"),
                                    null,
                                    context(scope)));
            assertThat(failed.path("exitCode").asInt()).isEqualTo(7);
            assertThat(failed.path("stderr").asText()).isEqualTo("expected-error");
        }
    }

    @Test
    void defaultDenyCwdAndArgumentBoundsAreEnforcedBeforeStart() throws Exception {
        var deny = new ShellTools(workspace, ShellToolProperties.defaults(List.of()));
        var allow = new ShellTools(workspace, properties());
        try (var scope = scope(Duration.ofSeconds(4))) {
            assertThatThrownBy(() ->
                            deny.shell(
                                    javaExecutable().toString(),
                                    arguments("action", "never"),
                                    null,
                                    context(scope)))
                    .hasMessageContaining("DENIED");
            assertThatThrownBy(() ->
                            allow.shell("java", arguments("action", "never"), null, context(scope)))
                    .hasMessageContaining("DENIED");
            assertThatThrownBy(() ->
                            allow.shell(
                                    javaExecutable().toString(),
                                    arguments("action", "never"),
                                    workspace.getParent().toString(),
                                    context(scope)))
                    .hasMessageContaining("DENIED");
            for (var args :
                    List.of(
                            java.util.Collections.nCopies(129, "x"),
                            List.of("x".repeat(65537)),
                            List.of("a\0b"))) {
                assertThatThrownBy(() ->
                                allow.shell(
                                        javaExecutable().toString(), args, null, context(scope)))
                        .hasMessageContaining("INVALID_ARGUMENT");
            }
            assertThat(Files.exists(workspace.resolve("never"))).isFalse();
        }
    }

    @Test
    void floodDrainsBothStreamsWithSharedByteCapAndValidJson() throws Exception {
        var tools = new ShellTools(workspace, properties());
        try (var scope = scope(Duration.ofSeconds(5))) {
            var result =
                    JsonMapper.builder()
                            .build()
                            .readTree(
                                    tools.shell(
                                            javaExecutable().toString(),
                                            arguments("flood"),
                                            null,
                                            context(scope)));
            assertThat(result.path("truncated").asBoolean()).isTrue();
            assertThat(result.path("cleanupConfirmed").asBoolean()).isTrue();
            assertThat(result.path("exitCode").asInt()).isZero();
            assertThat(result.toString().length()).isLessThanOrEqualTo(10000);
        }
    }
}
