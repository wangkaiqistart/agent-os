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
import org.junit.jupiter.api.Test;

class AgentOSCliTest {
    @Test
    void bootLauncherIsUsedForChatButInitDoesNotNeedIt() throws Exception {
        var workspace = Files.createTempDirectory("cli");
        Files.createDirectories(workspace.resolve("agents/assistant"));
        Files.writeString(
                workspace.resolve("agents/assistant/AGENT.md"),
                "---\nname: assistant\nprovider:\n  model: local-model\n---\n指令\n");
        AgentService service = mock(AgentService.class);
        when(service.process(org.mockito.ArgumentMatchers.any()))
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
                        "chat",
                        "--workspace",
                        workspace.toString(),
                        "--profile",
                        "assistant",
                        "--message",
                        "hello");

        assertThat(exit).isZero();
        verify(service).process(org.mockito.ArgumentMatchers.any());
        assertThat(
                        AgentOSCli.execute(
                                "init",
                                "--workspace",
                                workspace.toString(),
                                "--model",
                                "local-model"))
                .isZero();
    }
}
