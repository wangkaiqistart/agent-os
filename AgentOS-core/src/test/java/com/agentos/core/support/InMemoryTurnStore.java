package com.agentos.core.support;

import com.agentos.core.error.AgentOSException;
import com.agentos.core.error.ErrorCode;
import com.agentos.core.session.SessionKey;
import com.agentos.core.session.TurnSnapshot;
import com.agentos.core.session.TurnStore;
import com.agentos.core.turn.TurnExecutionBudget;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** 测试夹具以共享会话监视器模拟事务，正式实现只使用数据库协调。 */
public final class InMemoryTurnStore implements TurnStore {
    private final InMemorySessionManager sessions;
    private final Map<UUID, TurnSnapshot> turns = new HashMap<>();
    private final Map<UUID, FinishResult> results = new HashMap<>();

    public InMemoryTurnStore(InMemorySessionManager sessions) {
        this.sessions = sessions;
    }

    public TurnSnapshot startTurn(
            UUID id, SessionKey key, String trigger, String message, TurnExecutionBudget budget) {
        synchronized (sessions) {
            var session = sessions.create(key);
            if (session.leaseUntil() != null && session.leaseUntil().isAfter(Instant.now())) {
                throw new AgentOSException(ErrorCode.SESSION_BUSY, "上一条消息还在处理");
            }
            for (var old : List.copyOf(turns.values())) {
                if (old.sessionId().equals(session.sessionId()) && old.status().equals("RUNNING")) {
                    finishAndRelease(old, "INTERRUPTED", "本轮已中断", "租约失效");
                }
            }
            var turn =
                    new TurnSnapshot(
                            id,
                            session.sessionId(),
                            key,
                            id.toString(),
                            budget.deadline(),
                            "RUNNING");
            turns.put(id, turn);
            sessions.setLease(key, turn.owner(), budget.deadline().plusSeconds(150));
            return turn;
        }
    }

    public boolean renewLease(TurnSnapshot turn, TurnExecutionBudget budget) {
        synchronized (sessions) {
            var current = turns.get(turn.turnId());
            var session = sessions.findActive(turn.sessionKey()).orElse(null);
            return current != null
                    && current.status().equals("RUNNING")
                    && !budget.expired()
                    && session != null
                    && turn.owner().equals(session.leaseOwner())
                    && session.leaseUntil() != null
                    && session.leaseUntil().isAfter(Instant.now());
        }
    }

    public FinishResult finishAndRelease(
            TurnSnapshot turn, String status, String text, String error) {
        synchronized (sessions) {
            var previous = results.get(turn.turnId());
            if (previous != null) {
                return previous;
            }
            var session = sessions.findActive(turn.sessionKey()).orElse(null);
            if (session == null
                    || !turn.owner().equals(session.leaseOwner())
                    || session.leaseUntil() == null
                    || !session.leaseUntil().isAfter(Instant.now())) {
                status = "INTERRUPTED";
            }
            turns.put(
                    turn.turnId(),
                    new TurnSnapshot(
                            turn.turnId(),
                            turn.sessionId(),
                            turn.sessionKey(),
                            turn.owner(),
                            turn.deadline(),
                            status));
            var result = new FinishResult(status, true, text, error);
            results.put(turn.turnId(), result);
            if (session != null && turn.owner().equals(session.leaseOwner())) {
                sessions.setLease(turn.sessionKey(), null, null);
            }
            return result;
        }
    }

    /** 测试查看收尾时写入的状态、文本与错误信息。 */
    public FinishResult result(UUID turnId) {
        synchronized (sessions) {
            return results.get(turnId);
        }
    }

    public Optional<TurnSnapshot> find(UUID id) {
        synchronized (sessions) {
            return Optional.ofNullable(turns.get(id));
        }
    }

    public int reapInterrupted() {
        return 0;
    }
}
