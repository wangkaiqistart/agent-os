package com.agentos.core.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agentos.core.agent.AgentLoader;
import com.agentos.core.agent.ContextLoader;
import com.agentos.core.provider.ProviderCatalog;
import com.agentos.core.provider.ProviderResult;
import com.agentos.core.provider.ProviderService;
import com.agentos.core.session.MessageStore;
import com.agentos.core.session.SessionKey;
import com.agentos.core.session.TurnStore;
import com.agentos.core.support.AgentFixtures;
import com.agentos.core.support.InMemorySessionManager;
import com.agentos.core.support.InMemoryTurnStore;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

class LeaseLossTest {
    @TempDir Path root;

    @Test
    void lossAfterModelInterruptsWithoutAppendingOrCallingAgain() throws Exception {
        AgentFixtures.agent(root, "a");
        var sessions = new InMemorySessionManager();
        var turns = spy(new InMemoryTurnStore(sessions));
        var messages = mock(MessageStore.class);
        when(messages.recentTurns(any(), anyInt())).thenReturn(List.of());
        var provider = mock(ProviderService.class);
        when(provider.call(any()))
                .thenAnswer(invocation -> {
                    var current =
                            sessions.findActive(new SessionKey("test", "u", "a")).orElseThrow();
                    sessions.setLease(
                            current.key(), "new-owner", java.time.Instant.now().plusSeconds(300));
                    return new ProviderResult(
                            new ChatResponse(List.of(new Generation(new AssistantMessage("late")))),
                            List.of());
                });
        try (var service =
                new DefaultAgentService(
                        new AgentLoader(root.resolve("agents")),
                        new ContextLoader(root),
                        mock(ProviderCatalog.class),
                        sessions,
                        turns,
                        new ReActLoop(
                                provider,
                                messages,
                                new PromptBuilder(new ContextLoader(root)),
                                new TurnGuard(turns)),
                        new TurnBudgetRegistry())) {
            var result = service.process(new ProcessRequest("test", "u", "a", "hello", "test"));
            assertThat(result.status()).isEqualTo(TurnStatus.INTERRUPTED);
            assertThat(result.persistenceStatus()).isEqualTo(PersistenceStatus.CONFIRMED);
            verify(provider, times(1)).call(any());
            verify(messages, never()).append(any(), anyInt(), any());
            verify(turns, times(2)).renewLease(any(), any());
            assertThat(
                            sessions.findActive(new SessionKey("test", "u", "a"))
                                    .orElseThrow()
                                    .leaseOwner())
                    .isEqualTo("new-owner");
        }
    }

    @Test
    void deadlineIsCheckedBeforeLeaseOrCancellation() {
        var turns = mock(TurnStore.class);
        var budget = TurnExecutionBudget.start(Duration.ofNanos(1));
        budget.cancel();
        assertThatThrownBy(() -> new TurnGuard(turns).checkpoint(null, budget))
                .isInstanceOf(TurnDeadlineExceededException.class);
        verifyNoInteractions(turns);
    }
}
