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
}
