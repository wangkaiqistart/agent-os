package com.agentos.core.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentos.core.session.SessionKey;
import com.agentos.core.support.InMemoryMessageStore;
import com.agentos.core.support.InMemorySessionManager;
import com.agentos.core.support.InMemoryTurnStore;
import com.agentos.core.turn.TurnExecutionBudget;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;

class InMemoryContractsTest {
    @Test
    void appendsMessagesInOrder() {
        InMemorySessionManager sessions = new InMemorySessionManager();
        InMemoryTurnStore turns = new InMemoryTurnStore(sessions);
        InMemoryMessageStore messages = new InMemoryMessageStore();
        SessionKey key = new SessionKey("test", "user", "agent");
        var turn =
                turns.startTurn(
                        UUID.randomUUID(),
                        key,
                        "test",
                        "hello",
                        TurnExecutionBudget.start(Duration.ofSeconds(2)));
        messages.append(turn, 0, java.util.List.of(new UserMessage("hello")));
        assertThat(messages.recentTurns(sessions.findActive(key).orElseThrow(), 1))
                .hasSize(1)
                .first()
                .extracting(h -> h.messages())
                .asList()
                .hasSize(1);
        turns.finishAndRelease(turn, "SUCCEEDED", "ok", null);
        assertThat(turns.find(turn.turnId()).orElseThrow().status()).isEqualTo("SUCCEEDED");
    }
}
