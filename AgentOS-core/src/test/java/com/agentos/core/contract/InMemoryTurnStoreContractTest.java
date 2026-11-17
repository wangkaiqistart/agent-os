package com.agentos.core.contract;

import com.agentos.core.session.TurnStore;
import com.agentos.core.support.InMemorySessionManager;
import com.agentos.core.support.InMemoryTurnStore;

/** InMemory TurnStore 适配器执行共享轮次契约。 */
class InMemoryTurnStoreContractTest extends TurnStoreContractTest {
    private final InMemorySessionManager sessions = new InMemorySessionManager();
    private final InMemoryTurnStore store = new InMemoryTurnStore(sessions);

    @Override
    protected com.agentos.core.session.SessionManager sessions() {
        return sessions;
    }

    @Override
    protected void expire(com.agentos.core.session.TurnSnapshot turn) {
        sessions.setLease(turn.sessionKey(), turn.owner(), java.time.Instant.EPOCH);
    }

    @Override
    protected TurnStore store() {
        return store;
    }
}
