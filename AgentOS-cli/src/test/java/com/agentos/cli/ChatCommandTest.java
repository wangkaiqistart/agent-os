package com.agentos.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agentos.core.turn.AgentService;
import com.agentos.core.turn.PersistenceStatus;
import com.agentos.core.turn.TurnResult;
import com.agentos.core.turn.TurnStatus;
import java.nio.file.Files;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChatCommandTest {
    @Test
    void singleMessageUsesInjectedLauncherWithoutStartingSpring() throws Exception {
        var workspace = Files.createTempDirectory("chat-command");
        Files.createDirectories(workspace.resolve("agents/assistant"));
        AgentService service = mock(AgentService.class);
        when(service.process(any()))
                .thenReturn(
                        new TurnResult(
                                UUID.randomUUID(),
                                TurnStatus.SUCCEEDED,
                                "answer",
                                1,
                                PersistenceStatus.CONFIRMED));
        var closed = new boolean[1];
        ContextLauncher launcher =
                () ->
                        new ContextLauncher.RunningContext() {
                            @Override
                            public AgentService agentService() {
                                return service;
                            }

                            @Override
                            public void close() {
                                closed[0] = true;
                            }
                        };
        ChatCommand command = new ChatCommand(launcher);
        command.workspace = workspace;
        command.profile = "assistant";
        command.message = "hello";
        command.run();
        assertThat(closed[0]).isTrue();
    }
}
