package com.agentos.core.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AgentLoaderTest {
    @Test
    void loadsSafeFrontmatterAndDefaults() throws Exception {
        Path root = Files.createTempDirectory("agents");
        Path dir = root.resolve("weather");
        Files.createDirectories(dir);
        Files.writeString(
                dir.resolve("AGENT.md"),
                "---\nname: weather\nprovider:\n  model: test\n---\nBe concise.\n");
        LoadedAgent agent = new AgentLoader(root).load("weather");
        assertThat(agent.profile().providerProfile().model()).isEqualTo("test");
        assertThat(agent.profile().tools()).isEmpty();
        assertThat(agent.profile().settings()).isEqualTo(AgentSettings.defaults());
    }

    @Test
    void rejectsTraversalAndMissingModel() throws Exception {
        Path root = Files.createTempDirectory("agents");
        assertThatThrownBy(() -> new AgentLoader(root).load("../x"))
                .isInstanceOf(RuntimeException.class);
        Path dir = root.resolve("bad");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("AGENT.md"), "---\nname: bad\nprovider: {}\n---\n");
        assertThatThrownBy(() -> new AgentLoader(root).load("bad"))
                .hasMessageContaining("provider.model");
    }

    @Test
    void rejectsMismatchedNameMalformedFrontmatterAndNonDirectory() throws Exception {
        Path root = Files.createTempDirectory("agents-invalid");
        Path mismatch = root.resolve("mismatch");
        Files.createDirectories(mismatch);
        Files.writeString(
                mismatch.resolve("AGENT.md"), "---\nname: other\nprovider:\n  model: m\n---\n");
        assertThatThrownBy(() -> new AgentLoader(root).load("mismatch"))
                .hasMessageContaining("name");

        Path malformed = root.resolve("malformed");
        Files.createDirectories(malformed);
        Files.writeString(
                malformed.resolve("AGENT.md"), "---\nname: malformed\nprovider: [\n---\n");
        assertThatThrownBy(() -> new AgentLoader(root).load("malformed"))
                .hasMessageContaining("frontmatter");

        Files.writeString(root.resolve("plain"), "not a directory");
        assertThatThrownBy(() -> new AgentLoader(root).load("plain")).hasMessageContaining("目录不存在");
    }

    @Test
    void rejectsUnresolvedModelsAndLinksWithoutChangingCustomAgentRules() throws Exception {
        Path root = Files.createTempDirectory("agent-model-check");
        Path main = Files.createDirectories(root.resolve("main"));
        for (String model : java.util.List.of("${MODEL}", "{{MODEL}}", "<model>")) {
            Files.writeString(
                    main.resolve("AGENT.md"),
                    "---\nname: main\nprovider:\n  model: '" + model + "'\n---\n正文\n");
            assertThatThrownBy(() -> new AgentLoader(root).load("main"))
                    .hasMessageContaining("provider.model");
        }
        Path custom = Files.createDirectories(root.resolve("custom"));
        Files.writeString(
                custom.resolve("AGENT.md"), "---\nname: custom\nprovider:\n  model: actual\n---\n");
        assertThat(new AgentLoader(root).load("custom").profile().name()).isEqualTo("custom");
        Files.delete(main.resolve("AGENT.md"));
        Files.createSymbolicLink(main.resolve("AGENT.md"), custom.resolve("AGENT.md"));
        assertThatThrownBy(() -> new AgentLoader(root).load("main"))
                .hasMessageContaining("Agent 文件");
        Files.createSymbolicLink(root.resolve("linked"), custom);
        assertThatThrownBy(() -> new AgentLoader(root).load("linked"))
                .hasMessageContaining("目录不存在");
    }
}
