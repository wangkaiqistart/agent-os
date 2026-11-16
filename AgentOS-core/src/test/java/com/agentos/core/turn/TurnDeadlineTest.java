package com.agentos.core.turn;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentos.core.provider.ProviderCatalog;
import com.agentos.core.provider.ProviderRequest;
import com.agentos.core.provider.ProviderResult;
import com.agentos.core.support.AgentFixtures;
import com.agentos.core.support.InMemoryMessageStore;
import com.agentos.core.support.InMemorySessionManager;
import com.agentos.core.support.InMemoryTurnStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

class TurnDeadlineTest {
    @Test
    void slowProviderEndsAtSharedDeadlineAndStillConfirmsFinish() throws Exception {
        Path workspace = Files.createTempDirectory("agentos-deadline");
        AgentFixtures.agent(workspace, "a");
        Files.writeString(
                workspace.resolve("agents/a/AGENT.md"),
                "---\nname: a\nprovider:\n  model: test-model\nsettings:\n  turn_timeout: 50ms\n---\n测试\n");
        var sessions = new InMemorySessionManager();
        var turns = new InMemoryTurnStore(sessions);
        var provider =
                new com.agentos.core.provider.ProviderService() {
                    public ProviderResult call(ProviderRequest ignored) {
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                        return new ProviderResult(
                                new ChatResponse(
                                        List.of(new Generation(new AssistantMessage("迟到")))),
                                List.of());
                    }

                    public ProviderResult stream(
                            ProviderRequest request, java.util.function.Consumer<String> output) {
                        return call(request);
                    }
                };
        var service =
                new DefaultAgentService(
                        AgentFixtures.loader(workspace),
                        new com.agentos.core.agent.ContextLoader(workspace),
                        Mockito.mock(ProviderCatalog.class),
                        sessions,
                        turns,
                        new ReActLoop(
                                provider,
                                new InMemoryMessageStore(),
                                new PromptBuilder(
                                        new com.agentos.core.agent.ContextLoader(workspace)),
                                new TurnGuard(turns)),
                        new TurnBudgetRegistry());
        long started = System.nanoTime();

        var result = service.process(new ProcessRequest("cli", "deadline-user", "a", "你好", "cli"));

        long elapsed = System.nanoTime() - started;
        assertThat(result.status()).isEqualTo(TurnStatus.TIMED_OUT);
        assertThat(result.persistenceStatus()).isEqualTo(PersistenceStatus.CONFIRMED);
        assertThat(TimeUnit.NANOSECONDS.toMillis(elapsed)).isLessThan(1300);
    }
}
