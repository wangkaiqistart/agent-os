package com.agentos.core.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentos.core.session.SessionKey;
import com.agentos.core.session.TurnSnapshot;
import com.agentos.core.session.TurnStore;
import com.agentos.core.turn.TurnExecutionBudget;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** TurnStore 的共享最小契约。并发与接管场景由 US6/US5 的专门测试覆盖。 */
public abstract class TurnStoreContractTest {
    protected abstract TurnStore store();

    @Test
    void startsAndFinishesOneTurn() {
        UUID id = UUID.randomUUID();
        TurnSnapshot turn =
                store().startTurn(
                                id,
                                new SessionKey("contract", "user", "agent"),
                                "test",
                                "hello",
                                TurnExecutionBudget.start(Duration.ofSeconds(2)));
        assertThat(store().find(id))
                .hasValueSatisfying(snapshot -> assertThat(snapshot.status()).isEqualTo("RUNNING"));
        assertThat(store().finishAndRelease(turn, "SUCCEEDED", "ok", null).persistenceConfirmed())
                .isTrue();
        assertThat(store().find(id)).isEmpty();
    }
}
