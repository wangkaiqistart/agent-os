package com.agentos.core.support;

import com.agentos.core.session.SessionKey;
import com.agentos.core.session.SessionSnapshot;
import com.agentos.core.session.TurnSnapshot;
import com.agentos.core.session.TurnStore;
import com.agentos.core.turn.TurnExecutionBudget;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryTurnStore implements TurnStore {
    private final InMemorySessionManager sessions;
    private final Map<UUID, TurnSnapshot> turns = new ConcurrentHashMap<>();

    public InMemoryTurnStore(InMemorySessionManager sessions) {
        this.sessions = sessions;
    }

    public TurnSnapshot startTurn(
            UUID id, SessionKey key, String trigger, String message, TurnExecutionBudget budget) {
        SessionSnapshot session = sessions.create(key);
        if (session.leaseOwner() != null) {
            throw new IllegalStateException("busy");
        }
        TurnSnapshot turn =
                new TurnSnapshot(
                        id, session.sessionId(), key, id.toString(), budget.deadline(), "RUNNING");
        turns.put(id, turn);
        return turn;
    }

    public boolean renewLease(TurnSnapshot turn, TurnExecutionBudget budget) {
        return turns.containsKey(turn.turnId()) && !budget.expired();
    }

    public FinishResult finishAndRelease(
            TurnSnapshot turn, String status, String text, String error) {
        turns.remove(turn.turnId());
        return new FinishResult(status, true, text, error);
    }

    public Optional<TurnSnapshot> find(UUID id) {
        return Optional.ofNullable(turns.get(id));
    }

    public int reapInterrupted() {
        return 0;
    }
}
