package com.agentos.core.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentos.core.session.SessionKey;
import com.agentos.core.session.SessionManager;
import org.junit.jupiter.api.Test;

/** SessionManager 的共享最小契约；实现测试只需提供一个内存或 JPA 适配器。 */
public abstract class SessionManagerContractTest {
    protected abstract SessionManager manager();

    protected abstract SessionKey key();

    @Test
    void missingSessionIsReadOnlyAndArchiveIsNoop() {
        assertThat(manager().findActive(key())).isEmpty();
        assertThat(manager().archiveIfIdle(key())).isFalse();
        assertThat(manager().findActive(key())).isEmpty();
    }
}
