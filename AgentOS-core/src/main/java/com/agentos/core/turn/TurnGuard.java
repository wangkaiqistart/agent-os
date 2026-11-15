package com.agentos.core.turn;

import com.agentos.core.session.TurnSnapshot;
import com.agentos.core.session.TurnStore;
import java.util.Objects;

/** 模型调用、消息追加前的共享截止时间和租约检查。 */
public final class TurnGuard {
    private final TurnStore turnStore;

    public TurnGuard(TurnStore turnStore) {
        this.turnStore = Objects.requireNonNull(turnStore);
    }

    public void checkpoint(TurnSnapshot turn, TurnExecutionBudget budget) {
        if (budget.expired() || budget.hardStopped()) {
            throw new TurnDeadlineExceededException();
        }
        if (!turnStore.renewLease(turn, budget)) {
            throw new LeaseLostException();
        }
    }
}
