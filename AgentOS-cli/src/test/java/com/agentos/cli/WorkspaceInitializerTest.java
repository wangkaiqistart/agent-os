package com.agentos.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.core.agent.AgentLoader;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 仅在临时目录测试文件语义，写入失败由确定性的注入操作触发。 */
class WorkspaceInitializerTest {
    @TempDir Path directory;

    @Test
    void createsLoadableMainAndPreservesAllExistingFilesWithoutReadingNewOptions()
            throws Exception {
        Path root = directory.resolve("workspace");
        var first =
                new WorkspaceInitializer()
                        .initialize(root, () -> new DefaultAgentOptions("模型: '#[]\\n值", null));
        var loaded = new AgentLoader(root.resolve("agents")).load("main");
        assertThat(loaded.profile().providerProfile().model()).isEqualTo("模型: '#[]\\n值");
        assertThat(loaded.profile().providerProfile().provider()).isEqualTo("primary");
        assertThat(loaded.profile().providerProfile().temperature()).isNull();
        assertThat(loaded.profile().tools()).isEmpty();
        assertThat(loaded.instructions()).contains("助手");
        Files.writeString(root.resolve("AGENTS.md"), "custom");
        String main = Files.readString(root.resolve("agents/main/AGENT.md"));
        Files.delete(root.resolve("USER.md"));
        var second =
                new WorkspaceInitializer()
                        .initialize(
                                root,
                                () -> {
                                    throw new AssertionError("不应读新配置");
                                });
        assertThat(first.created()).isNotEmpty();
        assertThat(second.created()).containsExactly(root.resolve("USER.md"));
        assertThat(second.skipped()).contains(root.resolve("agents/main/AGENT.md"));
        assertThat(Files.readString(root.resolve("AGENTS.md"))).isEqualTo("custom");
        assertThat(Files.readString(root.resolve("agents/main/AGENT.md"))).isEqualTo(main);
    }

    @Test
    void providerAndModelAreSerializedAsStringsRatherThanYamlFragments() throws Exception {
        Path root = directory.resolve("workspace");
        new WorkspaceInitializer()
                .initialize(
                        root, () -> new DefaultAgentOptions("model\nother: value", "custom: '#[]"));
        var profile = new AgentLoader(root.resolve("agents")).load("main").profile();
        assertThat(profile.providerProfile().model()).isEqualTo("model\nother: value");
        assertThat(profile.providerProfile().provider()).isEqualTo("custom: '#[]");
        assertThat(profile.tools()).isEmpty();
    }

    @Test
    void missingModelDoesNotWriteAnything() {
        Path root = directory.resolve("workspace");
        assertThatThrownBy(() ->
                        new WorkspaceInitializer()
                                .initialize(root, () -> new DefaultAgentOptions(null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(root).doesNotExist();
    }

    @Test
    void preflightRejectsWrongTypesLinksAndBrokenMainBeforeWritingTemplates() throws Exception {
        Path root = Files.createDirectories(directory.resolve("workspace"));
        Files.writeString(root.resolve("agents"), "occupied");
        assertThatThrownBy(() ->
                        new WorkspaceInitializer()
                                .initialize(root, () -> new DefaultAgentOptions("model", null)))
                .isInstanceOf(IOException.class);
        assertThat(root.resolve("AGENTS.md")).doesNotExist();
        Files.delete(root.resolve("agents"));
        Path outside = Files.createDirectories(directory.resolve("outside"));
        Files.createSymbolicLink(root.resolve("agents"), outside);
        assertThatThrownBy(() ->
                        new WorkspaceInitializer()
                                .initialize(root, () -> new DefaultAgentOptions("model", null)))
                .isInstanceOf(IOException.class);
        assertThat(outside.resolve("main")).doesNotExist();
        Files.delete(root.resolve("agents"));
        Files.createDirectories(root.resolve("agents/main"));
        Files.writeString(root.resolve("agents/main/AGENT.md"), "broken");
        assertThatThrownBy(() ->
                        new WorkspaceInitializer()
                                .initialize(root, () -> new DefaultAgentOptions("model", null)))
                .hasMessageContaining("frontmatter");
        assertThat(root.resolve("AGENTS.md")).doesNotExist();
    }

    @Test
    void partialFailurePreservesCreatedFilesAndRetryOnlyFillsMissingItems() throws Exception {
        Path root = directory.resolve("workspace");
        WorkspaceInitializer init =
                new WorkspaceInitializer((file, content) -> {
                    if (file.endsWith("SOUL.md")) {
                        throw new IOException("synthetic-write-fault");
                    }
                    Files.writeString(file, content, StandardOpenOption.CREATE_NEW);
                });
        assertThatThrownBy(
                        () -> init.initialize(root, () -> new DefaultAgentOptions("model", null)))
                .isInstanceOf(IOException.class);
        assertThat(root.resolve("AGENTS.md")).exists();
        Files.writeString(root.resolve("AGENTS.md"), "preserved");
        new WorkspaceInitializer().initialize(root, () -> new DefaultAgentOptions("model", null));
        assertThat(Files.readString(root.resolve("AGENTS.md"))).isEqualTo("preserved");
        assertThat(root.resolve("agents/main/AGENT.md")).exists();
    }

    @Test
    void createNewCompetitionPreservesAndValidatesWinner() throws Exception {
        Path root = directory.resolve("workspace");
        WorkspaceInitializer init =
                new WorkspaceInitializer((file, content) -> {
                    Files.writeString(
                            file, content.replace("助手", "赢家助手"), StandardOpenOption.CREATE_NEW);
                    throw new FileAlreadyExistsException(file.toString());
                });
        var result = init.initialize(root, () -> new DefaultAgentOptions("model", null));
        assertThat(result.skipped()).contains(root.resolve("agents/main/AGENT.md"));
        assertThat(Files.readString(root.resolve("agents/main/AGENT.md"))).contains("赢家助手");
    }

    @Test
    void corruptPartialMainAndInvalidRaceWinnerAreNeverOverwrittenOnRetry() throws Exception {
        Path root = directory.resolve("workspace");
        WorkspaceInitializer init =
                new WorkspaceInitializer((file, content) -> {
                    if (file.endsWith("AGENT.md")) {
                        Files.writeString(file, "partial", StandardOpenOption.CREATE_NEW);
                        throw new IOException("synthetic-partial-write");
                    }
                    Files.writeString(file, content, StandardOpenOption.CREATE_NEW);
                });
        assertThatThrownBy(
                        () -> init.initialize(root, () -> new DefaultAgentOptions("model", null)))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() ->
                        new WorkspaceInitializer()
                                .initialize(
                                        root,
                                        () -> {
                                            throw new AssertionError("坏 main 不应重新解析模型");
                                        }))
                .hasMessageContaining("frontmatter");
        assertThat(Files.readString(root.resolve("agents/main/AGENT.md"))).isEqualTo("partial");
        Files.delete(root.resolve("agents/main/AGENT.md"));
        WorkspaceInitializer race =
                new WorkspaceInitializer((file, content) -> {
                    if (Files.exists(file)) {
                        throw new FileAlreadyExistsException(file.toString());
                    }
                    Files.writeString(file, "bad-winner", StandardOpenOption.CREATE_NEW);
                    throw new FileAlreadyExistsException(file.toString());
                });
        assertThatThrownBy(
                        () -> race.initialize(root, () -> new DefaultAgentOptions("model", null)))
                .hasMessageContaining("frontmatter");
        assertThat(Files.readString(root.resolve("agents/main/AGENT.md"))).isEqualTo("bad-winner");
    }
}
