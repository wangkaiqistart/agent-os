package com.agentos.core.support;

import com.agentos.core.agent.AgentLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class AgentFixtures {
    private AgentFixtures() {}

    public static Path agent(Path workspace, String name) throws IOException {
        Path dir = workspace.resolve("agents").resolve(name);
        Files.createDirectories(dir);
        Files.writeString(
                dir.resolve("AGENT.md"),
                "---\nname: "
                        + name
                        + "\nprovider:\n  model: test-model\n---\nYou are a test agent.\n");
        return dir;
    }

    public static AgentLoader loader(Path workspace) {
        return new AgentLoader(workspace.resolve("agents"));
    }
}
