package com.agentos.core.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ContextLoaderTest {
    @Test
    void preservesBootstrapOrderAndReloadsChanges() throws Exception {
        var workspace = Files.createTempDirectory("context-loader");
        var agentDir = workspace.resolve("agents/a");
        Files.createDirectories(agentDir);
        Files.writeString(
                agentDir.resolve("AGENT.md"), "---\nname: a\nprovider:\n  model: m\n---\nbody");
        Files.writeString(workspace.resolve("AGENTS.md"), "agents-v1");
        Files.writeString(workspace.resolve("SOUL.md"), "soul-v1");
        Files.writeString(workspace.resolve("USER.md"), "user-v1");
        var loader =
                new ContextLoader(
                        workspace,
                        Clock.fixed(Instant.parse("2026-01-02T00:00:00Z"), ZoneOffset.UTC));
        var agent = new AgentLoader(workspace.resolve("agents")).load("a");

        assertThat(loader.load(agent).bootstrap().keySet())
                .containsExactly("AGENTS.md", "SOUL.md", "USER.md");
        Files.writeString(workspace.resolve("SOUL.md"), "soul-v2");
        assertThat(loader.load(agent).bootstrap().get("SOUL.md")).isEqualTo("soul-v2");
        assertThat(loader.today()).hasToString("2026-01-02");
    }
}
