package com.agentos.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 入口级退出码与安全输出验证，全部配置为合成值。 */
class InitCommandBehaviorTest {
    @TempDir Path directory;

    @BeforeEach
    void config() throws Exception {
        Files.writeString(
                directory.resolve("application.yaml"),
                "agentos:\n  providers:\n    primary:\n      type: openai\n");
    }

    @Test
    void missingModelWritesNothingAndInitNeverLaunchesSpring() throws Exception {
        var out = new StringWriter();
        var line =
                AgentOSCli.commandLine(
                        root -> {
                            throw new AssertionError("init 不应启动 Spring");
                        },
                        directory,
                        key -> null);
        line.setOut(new PrintWriter(out));
        line.setErr(new PrintWriter(out));
        assertThat(line.execute("init", "--workspace", "workspace")).isEqualTo(2);
        assertThat(directory.resolve("workspace")).doesNotExist();
        assertThat(out.toString()).contains("--model").doesNotContain("Exception");
        assertThat(line.execute("init", "--workspace", "workspace", "--model", "test-model"))
                .isZero();
        assertThat(out.toString()).contains("新建", "跳过", "仅本地文件");
        assertThat(directory.resolve("workspace/agents/main/AGENT.md")).exists();
        assertThat(line.execute("init", "--workspace", "workspace2", "--model", "")).isEqualTo(2);
    }

    @Test
    void sourceAndPathFailuresHaveSafeDistinctExitCodes() throws Exception {
        var out = new StringWriter();
        var line = AgentOSCli.commandLine(null, directory, key -> null);
        line.setOut(new PrintWriter(out));
        line.setErr(new PrintWriter(out));
        assertThat(line.execute("init", "--workspace", "workspace")).isEqualTo(2);
        Files.writeString(directory.resolve("occupied"), "existing");
        assertThat(line.execute("init", "--workspace", "occupied", "--model", "test-model"))
                .isEqualTo(1);
        assertThat(out.toString()).doesNotContain("synthetic-secret", "BROKEN", "Exception");
    }

    @Test
    void secretKeysAreNotProjectedIntoGeneratedMainOrOutputAndBadMainIsPreserved()
            throws Exception {
        var out = new StringWriter();
        var line = AgentOSCli.commandLine(null, directory, key -> null);
        line.setOut(new PrintWriter(out));
        line.setErr(new PrintWriter(out));
        assertThat(line.execute("init", "--workspace", "workspace", "--model", "local-model"))
                .isZero();
        try (var files = Files.walk(directory.resolve("workspace"))) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                assertThat(Files.readString(file))
                        .doesNotContain("synthetic-secret", "synthetic-password");
            }
        }
        Path main = directory.resolve("workspace/agents/main/AGENT.md");
        Files.writeString(main, "broken-main");
        assertThat(line.execute("init", "--workspace", "workspace", "--model", "another"))
                .isEqualTo(2);
        assertThat(Files.readString(main)).isEqualTo("broken-main");
        assertThat(out.toString()).doesNotContain("synthetic-secret", "synthetic-password");
    }

    @Test
    void configuredProviderIsAcceptedAndUnknownProviderFailsBeforeDirectoryCreation()
            throws Exception {
        Files.writeString(
                directory.resolve("application.yaml"),
                "agentos:\n  providers:\n    primary: {}\n    custom: {}\n");
        var line = AgentOSCli.commandLine(null, directory, key -> null);
        line.setOut(new PrintWriter(new StringWriter()));
        line.setErr(new PrintWriter(new StringWriter()));
        assertThat(
                        line.execute(
                                "init",
                                "--workspace",
                                "custom",
                                "--model",
                                "m",
                                "--provider",
                                "custom"))
                .isZero();
        assertThat(Files.readString(directory.resolve("custom/agents/main/AGENT.md")))
                .contains("name: custom");
        assertThat(
                        line.execute(
                                "init",
                                "--workspace",
                                "unknown",
                                "--model",
                                "m",
                                "--provider",
                                "missing"))
                .isEqualTo(2);
        assertThat(directory.resolve("unknown")).doesNotExist();
    }
}
