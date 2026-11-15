package com.agentos.core.contract;

import com.agentos.core.session.TurnStore;
import com.agentos.core.support.InMemorySessionManager;
import com.agentos.core.support.InMemoryTurnStore;

/** InMemory TurnStore 适配器执行共享轮次契约。 */
class InMemoryTurnStoreContractTest extends TurnStoreContractTest {
    private final InMemorySessionManager sessions = new InMemorySessionManager();
    private final InMemoryTurnStore store = new InMemoryTurnStore(sessions);

    @Override
    protected TurnStore store() {
        return store;
    }
}
