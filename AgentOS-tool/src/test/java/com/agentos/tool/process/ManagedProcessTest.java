package com.agentos.tool.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class ManagedProcessTest {
    @TempDir Path workspace;

    @Test
    void environmentIsRebuiltFromExplicitAllowlistAndServiceDoesNotBorrowCommandSecrets()
            throws Exception {
        var properties =
                new ShellToolProperties(
                        List.of(), List.of("LANG"), 65536, Duration.ofSeconds(30), 10000);
        var policy =
                new ProcessEnvironmentPolicy(
                        properties,
                        name ->
                                Map.of(
                                                "LANG",
                                                "C",
                                                "PRIVATE_HOST_SECRET",
                                                "host-sentinel",
                                                "BUSINESS",
                                                "business-sentinel")
                                        .get(name));
        var environment = policy.build(Map.of("DECLARED", "${BUSINESS}"));
        assertThat(environment)
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of("LANG", "C", "DECLARED", "business-sentinel"));
        assertThatThrownBy(() -> policy.build(Map.of("MISSING", "${ABSENT}")))
                .hasMessageContaining("UNCONFIGURED")
                .hasMessageNotContaining("ABSENT");
        try (var scope = ShellToolsContractTest.scope(Duration.ofSeconds(4));
                var process =
                        ManagedProcess.start(
                                ShellToolsContractTest.javaExecutable(),
                                ShellToolsContractTest.arguments("env"),
                                workspace.toRealPath(),
                                environment,
                                scope)) {
            var result = BoundedProcessOutput.collect(process, scope, 65536);
            var actual = JsonMapper.builder().build().readTree(result.stdout());
            assertThat(actual.path("DECLARED").asText()).isEqualTo("business-sentinel");
            assertThat(actual.has("PRIVATE_HOST_SECRET")).isFalse();
            assertThat(actual.has("HOME")).isFalse();
        }
    }

    @Test
    void timeoutAndCancellationEachConfirmControlledProcessTreeTwentyTimes() throws Exception {
        var samples = new java.util.ArrayList<Map<String, Object>>();
        for (boolean cancelled : List.of(false, true)) {
            for (int sample = 0; sample < 20; sample++) {
                Path pidFile = workspace.resolve("child-" + cancelled + "-" + sample);
                try (var scope = ShellToolsContractTest.scope(Duration.ofMillis(700));
                        var process =
                                ManagedProcess.start(
                                        ShellToolsContractTest.javaExecutable(),
                                        ShellToolsContractTest.arguments(
                                                "tree", pidFile.toString()),
                                        workspace.toRealPath(),
                                        Map.of(),
                                        scope)) {
                    long waitUntil = System.nanoTime() + Duration.ofSeconds(2).toNanos();
                    while (!Files.exists(pidFile) && System.nanoTime() < waitUntil) {
                        Thread.sleep(5);
                    }
                    assertThat(Files.exists(pidFile)).as("子进程已启动").isTrue();
                    long childPid = Long.parseLong(Files.readString(pidFile));
                    if (cancelled) {
                        scope.budget().cancel();
                    }
                    long started = System.nanoTime();
                    assertThatThrownBy(() -> BoundedProcessOutput.collect(process, scope, 65536))
                            .isInstanceOf(
                                    cancelled
                                            ? com.agentos.core.turn.TurnDeadlineExceededException
                                                    .class
                                            : com.agentos.core.tool.ToolExecutionScope
                                                    .ToolTimeoutException.class);
                    assertThat(process.process().isAlive()).isFalse();
                    assertThat(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false))
                            .isFalse();
                    assertThat(process.cleanupConfirmed()).isTrue();
                    long elapsedNanos = System.nanoTime() - started;
                    assertThat(Duration.ofNanos(elapsedNanos)).isLessThan(Duration.ofMillis(1750));
                    samples.add(
                            Map.of(
                                    "mode",
                                    cancelled ? "cancel" : "timeout",
                                    "elapsedMs",
                                    elapsedNanos / 1000000.0,
                                    "elapsedFromScopeMs",
                                    (System.nanoTime()
                                                    - scope.deadlineNanos()
                                                    + Duration.ofMillis(700).toNanos())
                                            / 1000000.0,
                                    "toolBudgetMs",
                                    700,
                                    "rootExited",
                                    !process.process().isAlive(),
                                    "childExited",
                                    !ProcessHandle.of(childPid)
                                            .map(ProcessHandle::isAlive)
                                            .orElse(false),
                                    "cleanupConfirmed",
                                    process.cleanupConfirmed()));
                }
            }
        }
        Files.writeString(
                Path.of("target/tool-process-cleanup-samples.json"),
                JsonMapper.builder()
                        .build()
                        .writeValueAsString(
                                Map.of(
                                        "status",
                                        "PASS",
                                        "time",
                                        java.time.ZonedDateTime.now(
                                                        java.time.ZoneId.of("Asia/Shanghai"))
                                                .toString(),
                                        "samples",
                                        samples)));
    }

    @Test
    void failedTerminationIsExplicitlyUnconfirmedBeforeFixtureFallbackCleanup() throws Exception {
        var builder =
                new ProcessBuilder(
                        ShellToolsContractTest.javaExecutable().toString(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        ManagedProcessFixture.class.getName(),
                        "sleep");
        builder.environment().clear();
        var process = builder.start();
        try (var scope = ShellToolsContractTest.scope(Duration.ofSeconds(3))) {
            var managed = new ManagedProcess(process, scope, handle -> {});
            assertThat(managed.terminate(System.nanoTime() + Duration.ofMillis(30).toNanos()))
                    .isFalse();
            assertThat(process.isAlive()).isTrue();
            assertThat(managed.cleanupConfirmed()).isFalse();
            scope.close();
            assertThat(scope.cleanupConfirmed()).isFalse();
        } finally {
            process.destroyForcibly();
            assertThat(process.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void directFloodRetainsAtMostSharedRawByteCap() throws Exception {
        try (var scope = ShellToolsContractTest.scope(Duration.ofSeconds(4));
                var process =
                        ManagedProcess.start(
                                ShellToolsContractTest.javaExecutable(),
                                ShellToolsContractTest.arguments("flood"),
                                workspace,
                                Map.of(),
                                scope)) {
            var result = BoundedProcessOutput.collect(process, scope, 65536);
            assertThat(result.retainedBytes()).isEqualTo(65536);
            assertThat(result.truncated()).isTrue();
            assertThat(result.exitCode()).isZero();
            assertThat(result.cleanupConfirmed()).isTrue();
        }
    }

    @Test
    void cancelledBeforeStartHasNoProcessOrFile() throws Exception {
        try (var scope = ShellToolsContractTest.scope(Duration.ofSeconds(2))) {
            scope.budget().cancel();
            assertThatThrownBy(() ->
                            ManagedProcess.start(
                                    ShellToolsContractTest.javaExecutable(),
                                    ShellToolsContractTest.arguments(
                                            "action", workspace.resolve("never").toString()),
                                    workspace,
                                    Map.of(),
                                    scope))
                    .isInstanceOf(com.agentos.core.turn.TurnDeadlineExceededException.class);
            assertThat(Files.exists(workspace.resolve("never"))).isFalse();
        }
    }
}
