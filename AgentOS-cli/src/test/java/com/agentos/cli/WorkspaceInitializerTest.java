package com.agentos.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import org.junit.jupiter.api.Test;

class WorkspaceInitializerTest {
    @Test
    void initializationIsIdempotentAndDoesNotOverwrite() throws Exception {
        var root = Files.createTempDirectory("agentos-init");
        var first = new WorkspaceInitializer().initialize(root);
        Files.writeString(root.resolve("AGENTS.md"), "custom");
        var second = new WorkspaceInitializer().initialize(root);
        assertThat(first.created()).isNotEmpty();
        assertThat(second.skipped()).isNotEmpty();
        assertThat(Files.readString(root.resolve("AGENTS.md"))).isEqualTo("custom");
    }
}
