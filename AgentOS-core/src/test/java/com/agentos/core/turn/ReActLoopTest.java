package com.agentos.core.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.agentos.core.agent.ContextLoader;
import com.agentos.core.agent.LoadedAgent;
import com.agentos.core.provider.ProviderRequest;
import com.agentos.core.provider.ProviderResult;
import com.agentos.core.provider.ProviderService;
import com.agentos.core.session.SessionKey;
import com.agentos.core.session.TurnSnapshot;
import com.agentos.core.support.AgentFixtures;
import com.agentos.core.support.InMemoryMessageStore;
import com.agentos.core.support.InMemorySessionManager;
import com.agentos.core.support.InMemoryTurnStore;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

class ReActLoopTest {
    @Test
    void restoresPreviousCompleteTurnsBeforeCurrentUserMessage() throws Exception {
        var workspace = Files.createTempDirectory("loop-history");
        AgentFixtures.agent(workspace, "a");
        LoadedAgent agent = AgentFixtures.loader(workspace).load("a");
        ProviderService provider = Mockito.mock(ProviderService.class);
        when(provider.call(any()))
                .thenReturn(
                        new ProviderResult(
                                new ChatResponse(
                                        List.of(new Generation(new AssistantMessage("answer")))),
                                List.of()));
        InMemorySessionManager sessions = new InMemorySessionManager();
        InMemoryTurnStore turns = new InMemoryTurnStore(sessions);
        InMemoryMessageStore messages = new InMemoryMessageStore();
        SessionKey key = new SessionKey("cli", "u", "a");
        TurnSnapshot previous =
                turns.startTurn(
                        UUID.randomUUID(),
                        key,
                        "cli",
                        "old question",
                        TurnExecutionBudget.start(Duration.ofSeconds(2)));
        messages.append(previous, 0, List.of(new UserMessage("old question")));
        turns.finishAndRelease(previous, "SUCCEEDED", "old answer", null);
        TurnSnapshot current =
                turns.startTurn(
                        UUID.randomUUID(),
                        key,
                        "cli",
                        "current",
                        TurnExecutionBudget.start(Duration.ofSeconds(2)));

        new ReActLoop(
                        provider,
                        messages,
                        new PromptBuilder(new ContextLoader(workspace)),
                        new TurnGuard(turns))
                .run(
                        current,
                        agent,
                        "current",
                        TurnExecutionBudget.start(Duration.ofSeconds(2)),
                        TurnSink.NOOP);

        ArgumentCaptor<ProviderRequest> request = ArgumentCaptor.forClass(ProviderRequest.class);
        Mockito.verify(provider).call(request.capture());
        assertThat(request.getValue().messages())
                .extracting(org.springframework.ai.chat.messages.Message::getText)
                .contains("old question", "current");
    }

    @Test
    void plainTextUsesOneCallAndPersistsAssistantMessage() throws Exception {
        var workspace = Files.createTempDirectory("loop");
        AgentFixtures.agent(workspace, "a");
        LoadedAgent agent = AgentFixtures.loader(workspace).load("a");
        ProviderService provider = Mockito.mock(ProviderService.class);
        when(provider.call(any()))
                .thenReturn(
                        new ProviderResult(
                                new ChatResponse(
                                        List.of(new Generation(new AssistantMessage("answer")))),
                                List.of()));
        InMemorySessionManager sessions = new InMemorySessionManager();
        InMemoryTurnStore turns = new InMemoryTurnStore(sessions);
        InMemoryMessageStore messages = new InMemoryMessageStore();
        var turn =
                turns.startTurn(
                        UUID.randomUUID(),
                        new SessionKey("cli", "u", "a"),
                        "cli",
                        "hello",
                        TurnExecutionBudget.start(Duration.ofSeconds(2)));
        var loop =
                new ReActLoop(
                        provider,
                        messages,
                        new PromptBuilder(new ContextLoader(workspace)),
                        new TurnGuard(turns));
        ReActLoop.Result result =
                loop.run(
                        turn,
                        agent,
                        "hello",
                        TurnExecutionBudget.start(Duration.ofSeconds(2)),
                        TurnSink.NOOP);
        assertThat(result.status()).isEqualTo(TurnStatus.SUCCEEDED);
        assertThat(result.text()).isEqualTo("answer");
        assertThat(result.iterations()).isEqualTo(1);
    }

    @Test
    void blankAssistantResponseFailsWithoutAppendingAssistantMessage() throws Exception {
        var workspace = Files.createTempDirectory("loop-empty");
        AgentFixtures.agent(workspace, "a");
        LoadedAgent agent = AgentFixtures.loader(workspace).load("a");
        ProviderService provider = Mockito.mock(ProviderService.class);
        when(provider.call(any()))
                .thenReturn(
                        new ProviderResult(
                                new ChatResponse(
                                        List.of(new Generation(new AssistantMessage("  ")))),
                                List.of()));
        InMemorySessionManager sessions = new InMemorySessionManager();
        InMemoryTurnStore turns = new InMemoryTurnStore(sessions);
        InMemoryMessageStore messages = new InMemoryMessageStore();
        var key = new SessionKey("cli", "u", "a");
        var turn =
                turns.startTurn(
                        UUID.randomUUID(),
                        key,
                        "cli",
                        "hello",
                        TurnExecutionBudget.start(Duration.ofSeconds(2)));

        var result =
                new ReActLoop(
                                provider,
                                messages,
                                new PromptBuilder(new ContextLoader(workspace)),
                                new TurnGuard(turns))
                        .run(
                                turn,
                                agent,
                                "hello",
                                TurnExecutionBudget.start(Duration.ofSeconds(2)),
                                TurnSink.NOOP);

        assertThat(result.status()).isEqualTo(TurnStatus.FAILED);
        assertThat(messages.recentTurns(sessions.findActive(key).orElseThrow(), 1)).isEmpty();
    }
}
