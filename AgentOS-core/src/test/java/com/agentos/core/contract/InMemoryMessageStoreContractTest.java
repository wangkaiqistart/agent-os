package com.agentos.core.contract;

import com.agentos.core.session.MessageStore;
import com.agentos.core.session.SessionKey;
import com.agentos.core.session.SessionSnapshot;
import com.agentos.core.session.TurnSnapshot;
import com.agentos.core.support.InMemoryMessageStore;
import com.agentos.core.support.InMemorySessionManager;
import com.agentos.core.support.InMemoryTurnStore;
import com.agentos.core.turn.TurnExecutionBudget;
import java.time.Duration;
import java.util.UUID;

/** InMemory MessageStore 适配器执行共享消息契约。 */
class InMemoryMessageStoreContractTest extends MessageStoreContractTest {
    private final InMemorySessionManager sessions = new InMemorySessionManager();
    private final InMemoryTurnStore turns = new InMemoryTurnStore(sessions);
    private final InMemoryMessageStore messages = new InMemoryMessageStore();
    private final SessionKey key = new SessionKey("contract", "user", "agent");
    private final TurnSnapshot turn =
            turns.startTurn(
                    UUID.randomUUID(),
                    key,
                    "test",
                    "hello",
                    TurnExecutionBudget.start(Duration.ofSeconds(2)));

    @Override
    protected MessageStore messages() {
        return messages;
    }

    @Override
    protected SessionSnapshot session() {
        return sessions.findActive(key).orElseThrow();
    }

    @Override
    protected TurnSnapshot turn() {
        return turn;
    }
}
