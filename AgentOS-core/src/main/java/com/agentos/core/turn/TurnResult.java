package com.agentos.core.turn;

import java.util.UUID;

public record TurnResult(
        UUID turnId,
        TurnStatus status,
        String text,
        int iterations,
        PersistenceStatus persistenceStatus) {
    public TurnResult {
        text = text == null ? "" : text;
        persistenceStatus =
                persistenceStatus == null ? PersistenceStatus.CONFIRMED : persistenceStatus;
    }
}
