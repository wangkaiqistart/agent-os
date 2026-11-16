package com.agentos.storage.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.agentos.core.session.HistoryTurn;
import com.agentos.core.session.SessionKey;
import com.agentos.core.session.SessionSnapshot;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;

/** 消息存储恢复完整工具结构，并排除当前仍在执行的轮次。 */
class JpaMessageStoreTest {
    @Test
    void restoresToolCallsAndResponsesWithoutIncludingRunningTurn() {
        SessionMessageRepository messages = Mockito.mock(SessionMessageRepository.class);
        AgentTurnRepository turns = Mockito.mock(AgentTurnRepository.class);
        UUID sessionId = UUID.randomUUID();
        UUID runningId = UUID.randomUUID();
        UUID doneId = UUID.randomUUID();
        Instant now = Instant.now();
        AgentTurnEntity running =
                new AgentTurnEntity(runningId, sessionId, "cli", now, now.plusSeconds(60));
        AgentTurnEntity done =
                new AgentTurnEntity(
                        doneId, sessionId, "cli", now.plusSeconds(1), now.plusSeconds(60));
        done.finish("SUCCEEDED", "done", null, now.plusSeconds(2));
        when(turns.findBySessionIdAndStatusNotOrderByStartedAtAsc(sessionId, "RUNNING"))
                .thenReturn(List.of(done));
        when(messages.findBySessionIdOrderByIdAsc(sessionId))
                .thenReturn(
                        List.of(
                                new SessionMessageEntity(
                                        sessionId,
                                        runningId,
                                        0,
                                        "USER",
                                        "in flight",
                                        null,
                                        null,
                                        Map.of(),
                                        now),
                                new SessionMessageEntity(
                                        sessionId,
                                        doneId,
                                        1,
                                        "ASSISTANT",
                                        "",
                                        List.of(
                                                new AssistantMessage.ToolCall(
                                                        "call-1", "function", "http_get", "{}")),
                                        null,
                                        Map.of(),
                                        now),
                                new SessionMessageEntity(
                                        sessionId,
                                        doneId,
                                        1,
                                        "TOOL",
                                        "",
                                        null,
                                        List.of(
                                                new ToolResponseMessage.ToolResponse(
                                                        "call-1", "http_get", "ok")),
                                        Map.of(),
                                        now)));

        var history =
                new JpaMessageStore(messages, turns)
                        .recentTurns(
                                new SessionSnapshot(
                                        sessionId,
                                        new SessionKey("cli", "u", "a"),
                                        "active",
                                        null,
                                        null),
                                20);

        assertThat(history).hasSize(1);
        assertThat(history.getFirst().messages()).hasSize(2);
        assertThat(history.getFirst().messages().get(0)).isInstanceOf(AssistantMessage.class);
        assertThat(((AssistantMessage) history.getFirst().messages().get(0)).getToolCalls())
                .extracting(AssistantMessage.ToolCall::id)
                .containsExactly("call-1");
        assertThat(history.getFirst().messages().get(1)).isInstanceOf(ToolResponseMessage.class);
        assertThat(((ToolResponseMessage) history.getFirst().messages().get(1)).getResponses())
                .extracting(ToolResponseMessage.ToolResponse::responseData)
                .containsExactly("ok");
    }

    @Test
    void selectsTwentyCompletedTurnsBeforeReadingMessages() {
        SessionMessageRepository messages = Mockito.mock(SessionMessageRepository.class);
        AgentTurnRepository turns = Mockito.mock(AgentTurnRepository.class);
        UUID sessionId = UUID.randomUUID();
        Instant now = Instant.now();
        List<AgentTurnEntity> completed = new ArrayList<>();
        List<SessionMessageEntity> rows = new ArrayList<>();
        for (int i = 1; i <= 21; i++) {
            UUID turnId = UUID.randomUUID();
            AgentTurnEntity turn =
                    new AgentTurnEntity(
                            turnId, sessionId, "cli", now.plusSeconds(i), now.plusSeconds(120));
            turn.finish("SUCCEEDED", "answer-" + i, null, now.plusSeconds(i + 1));
            completed.add(turn);
            rows.add(
                    new SessionMessageEntity(
                            sessionId,
                            turnId,
                            0,
                            "USER",
                            "question-" + i,
                            null,
                            null,
                            Map.of(),
                            now.plusSeconds(i)));
        }
        UUID runningId = UUID.randomUUID();
        rows.add(
                new SessionMessageEntity(
                        sessionId,
                        runningId,
                        0,
                        "USER",
                        "in-flight",
                        null,
                        null,
                        Map.of(),
                        now.plusSeconds(30)));
        when(turns.findBySessionIdAndStatusNotOrderByStartedAtAsc(sessionId, "RUNNING"))
                .thenReturn(completed);
        when(messages.findBySessionIdOrderByIdAsc(sessionId)).thenReturn(rows);

        List<HistoryTurn> history =
                new JpaMessageStore(messages, turns)
                        .recentTurns(
                                new SessionSnapshot(
                                        sessionId,
                                        new SessionKey("cli", "u", "a"),
                                        "active",
                                        null,
                                        null),
                                20);

        assertThat(history).hasSize(20);
        assertThat(history).allSatisfy(turn -> assertThat(turn.status()).isEqualTo("SUCCEEDED"));
        assertThat(history.getFirst().messages().getFirst().getText()).isEqualTo("question-2");
        assertThat(history.getLast().messages().getFirst().getText()).isEqualTo("question-21");
    }
}
