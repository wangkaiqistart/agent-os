package com.agentos.core.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentMcpAuthorizationTest {
    @TempDir Path root;

    @Test
    void orderedDeclarationsAreImmutableAndTheOldConstructorHasNoRemotePermission()
            throws Exception {
        write("[news, ' ops ', '']");
        var profile = new AgentLoader(root).load("demo").profile();
        assertThat(profile.mcpServers()).containsExactly("news", "ops");
        assertThatThrownBy(() -> profile.mcpServers().add("other"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(
                        new AgentProfile(
                                        profile.name(),
                                        profile.description(),
                                        profile.providerProfile(),
                                        profile.tools(),
                                        profile.settings())
                                .mcpServers())
                .isEmpty();
    }

    @Test
    void malformedOrDuplicateDeclarationsCannotSilentlyRemoveTheAuthorizationBoundary()
            throws Exception {
        for (String value : List.of("news", "[news, news]", "[UPPER]", "[123]", "{news: true}")) {
            write(value);
            assertThatThrownBy(() -> new AgentLoader(root).load("demo"))
                    .isInstanceOf(RuntimeException.class);
        }
    }

    private void write(String servers) throws Exception {
        Path directory = Files.createDirectories(root.resolve("demo"));
        Files.writeString(
                directory.resolve("AGENT.md"),
                "---\nname: demo\nprovider:\n  model: test\nmcp_servers: "
                        + servers
                        + "\n---\n测试。\n");
    }
}
