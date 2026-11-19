package com.agentos.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentos.core.tool.ToolCatalogView;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ToolListCommandTest {
    @TempDir Path root;

    @Test
    void listUsesCatalogLauncherAndJsonMatchesSafeProjectionWithoutExecution() {
        var view =
                new ToolCatalogView(
                        "v1",
                        java.time.Instant.parse("2026-11-18T00:00:00Z"),
                        "demo",
                        List.of(
                                new ToolCatalogView.Item(
                                        "fixture", "说明", "java:fixture", "AVAILABLE", true, true)),
                        List.of());
        var launches = new java.util.concurrent.atomic.AtomicInteger();
        var closes = new java.util.concurrent.atomic.AtomicInteger();
        ContextLauncher launcher =
                new ContextLauncher() {
                    public RunningContext launch(Path workspace) {
                        throw new AssertionError("不得启动聊天");
                    }

                    public CatalogContext launchCatalog(
                            Path workspace, Path config, boolean serve) {
                        launches.incrementAndGet();
                        assertThat(workspace).isEqualTo(root);
                        assertThat(serve).isFalse();
                        return new CatalogContext() {
                            public com.agentos.core.tool.ToolCatalogQuery query() {
                                return agent -> {
                                    assertThat(agent).isEqualTo("demo");
                                    return view;
                                };
                            }

                            public void close() {
                                closes.incrementAndGet();
                            }
                        };
                    }
                };
        var output = new StringWriter();
        var cli = AgentOSCli.commandLine(launcher, root, key -> null);
        cli.setOut(new PrintWriter(output));
        cli.setErr(new PrintWriter(output));
        assertThat(
                        cli.execute(
                                "tool",
                                "list",
                                "--workspace",
                                root.toString(),
                                "--agent",
                                "demo",
                                "--json"))
                .isZero();
        assertThat(
                        tools.jackson.databind.json.JsonMapper.builder()
                                .build()
                                .readTree(output.toString())
                                .path("version")
                                .asText())
                .isEqualTo("v1");
        assertThat(output.toString()).doesNotContain("inputSchema", "callback");
        assertThat(launches).hasValue(1);
        assertThat(closes).hasValue(1);
    }

    @Test
    void standaloneAndUsageFailuresAreSafeAndHelpNeverLaunches() {
        var output = new StringWriter();
        var cli = AgentOSCli.commandLine(null, root, key -> null);
        cli.setOut(new PrintWriter(output));
        cli.setErr(new PrintWriter(output));
        assertThat(cli.execute("tool", "list")).isEqualTo(1);
        assertThat(output.toString()).contains("启动器");
        assertThat(cli.execute("tool", "list", "--agent", "x".repeat(129))).isEqualTo(2);
        assertThat(cli.execute("tool", "list", "--help")).isZero();
    }
}
