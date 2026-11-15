package com.agentos.core.contract;

import com.agentos.core.session.SessionKey;
import com.agentos.core.session.SessionManager;
import com.agentos.core.support.InMemorySessionManager;

/** InMemory SessionManager 适配器执行共享会话契约。 */
class InMemorySessionManagerContractTest extends SessionManagerContractTest {
    private final InMemorySessionManager manager = new InMemorySessionManager();

    @Override
    protected SessionManager manager() {
        return manager;
    }

    @Override
    protected SessionKey key() {
        return new SessionKey("contract", "user", "agent");
    }
}
