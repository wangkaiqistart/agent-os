package com.agentos.core.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentos.core.agent.AgentLoader;
import com.agentos.core.agent.ContextLoader;
import com.agentos.core.error.AgentOSException;
import com.agentos.core.error.ErrorCode;
import com.agentos.core.provider.ProviderCatalog;
import com.agentos.core.provider.ProviderResult;
import com.agentos.core.provider.ProviderService;
import com.agentos.core.session.MessageStore;
import com.agentos.core.session.SessionKey;
import com.agentos.core.support.AgentFixtures;
import com.agentos.core.support.InMemorySessionManager;
import com.agentos.core.support.InMemoryTurnStore;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/** 闸门停住模型，同会话拒绝、多会话推进均经真实 core 入口。 */
class AgentServiceConcurrencyTest {
    @TempDir Path root;

    @Test
    void sameSessionIsBusyWhileOtherSessionProgressesAndFirstStillSucceeds() throws Exception {
        AgentFixtures.agent(root, "a");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var provider = mock(ProviderService.class);
        when(provider.call(any()))
                .thenAnswer(invocation -> {
                    if (calls.incrementAndGet() == 1) {
                        entered.countDown();
                        assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                    }
                    return new ProviderResult(
                            new ChatResponse(List.of(new Generation(new AssistantMessage("ok")))),
                            List.of());
                });
        var sessions = new InMemorySessionManager();
        var turns = new InMemoryTurnStore(sessions);
        var messages = mock(MessageStore.class);
        when(messages.recentTurns(any(), anyInt())).thenReturn(List.of());
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
                                new TurnBudgetRegistry());
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first =
                    executor.submit(() ->
                            service.process(new ProcessRequest("test", "u", "a", "first", "test")));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() ->
                                service.process(
                                        new ProcessRequest("test", "u", "a", "rejected", "test")))
                        .isInstanceOfSatisfying(
                                AgentOSException.class,
                                e ->
                                        assertThat(e.getErrorCode())
                                                .isEqualTo(ErrorCode.SESSION_BUSY));
                assertThatThrownBy(() -> sessions.archiveIfIdle(new SessionKey("test", "u", "a")))
                        .isInstanceOf(AgentOSException.class);
                verify(messages, never()).append(any(), anyInt(), any());
                assertThat(
                                service.process(
                                                new ProcessRequest(
                                                        "test", "other", "a", "parallel", "test"))
                                        .status())
                        .isEqualTo(TurnStatus.SUCCEEDED);
                assertThat(first).isNotDone();
            } finally {
                release.countDown();
            }
            assertThat(first.get(5, TimeUnit.SECONDS).status()).isEqualTo(TurnStatus.SUCCEEDED);
            assertThat(calls).hasValue(2);
        }
    }
}
