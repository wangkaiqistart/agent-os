package com.agentos.core.session;

import com.agentos.core.turn.TurnExecutionBudget;
import java.util.Optional;
import java.util.UUID;

/** 轮次租约与状态端口。实现必须用短事务完成每次操作。 */
public interface TurnStore {
    TurnSnapshot startTurn(
            UUID turnId,
            SessionKey key,
            String trigger,
            String userMessage,
            TurnExecutionBudget budget);

    boolean renewLease(TurnSnapshot turn, TurnExecutionBudget budget);

    FinishResult finishAndRelease(
            TurnSnapshot turn, String status, String text, String errorMessage);

    Optional<TurnSnapshot> find(UUID turnId);

    int reapInterrupted();

    record FinishResult(
            String status, boolean persistenceConfirmed, String text, String errorMessage) {}
}
