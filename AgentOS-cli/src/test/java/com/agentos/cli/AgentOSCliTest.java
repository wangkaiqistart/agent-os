package com.agentos.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentos.core.turn.AgentService;
import com.agentos.core.turn.PersistenceStatus;
import com.agentos.core.turn.TurnResult;
import com.agentos.core.turn.TurnStatus;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AgentOSCliTest {
    @Test
    void helpVersionAndUnknownOptionDoNotStartLauncher() throws Exception {
        AtomicInteger launches = new AtomicInteger();
        ContextLauncher forbidden =
                root -> {
                    launches.incrementAndGet();
                    throw new AssertionError("轻命令不应启动");
                };
        var workspace = Files.createTempDirectory("cli-help");
        var line = AgentOSCli.commandLine(forbidden, workspace, key -> null);
        assertThat(line.execute("--help")).isZero();
        assertThat(line.execute("--version")).isZero();
        assertThat(line.execute("--unknown")).isEqualTo(2);
        assertThat(launches).hasValue(0);
    }

    @Test
    void bootLauncherIsUsedForChatButInitDoesNotNeedIt() throws Exception {
        var workspace = Files.createTempDirectory("cli");
        Files.writeString(
                workspace.resolve("application.yaml"),
                "agentos:\n  providers:\n    primary:\n      type: openai\n");
        Files.createDirectories(workspace.resolve("agents/assistant"));
        Files.writeString(
                workspace.resolve("agents/assistant/AGENT.md"),
                "---\nname: assistant\nprovider:\n  model: local-model\n---\n指令\n");
        AgentService service = mock(AgentService.class);
        when(service.process(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(com.agentos.core.turn.TurnSink.class)))
                .thenReturn(
                        new TurnResult(
                                UUID.randomUUID(),
                                TurnStatus.SUCCEEDED,
                                "answer",
                                1,
                                PersistenceStatus.CONFIRMED));
        var launcher =
                new ContextLauncher() {
                    @Override
                    public RunningContext launch(java.nio.file.Path workspaceRoot) {
                        return new RunningContext() {
                            @Override
                            public AgentService agentService() {
                                return service;
                            }

                            @Override
                            public void close() {}
                        };
                    }
                };

        int exit =
                AgentOSCli.execute(
                        launcher,
                        "--config",
                        workspace.resolve("application.yaml").toString(),
                        "chat",
                        "--workspace",
                        workspace.toString(),
                        "--profile",
                        "assistant",
                        "--message",
                        "hello");

        assertThat(exit).isZero();
        verify(service)
                .process(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(com.agentos.core.turn.TurnSink.class));
        assertThat(
                        AgentOSCli.execute(
                                "--config",
                                workspace.resolve("application.yaml").toString(),
                                "init",
                                "--workspace",
                                workspace.toString(),
                                "--model",
                                "local-model"))
                .isZero();
    }
}
