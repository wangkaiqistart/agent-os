package com.agentos.storage.session;

import com.agentos.core.error.AgentOSException;
import com.agentos.core.error.ErrorCode;
import com.agentos.core.session.SessionKey;
import com.agentos.core.session.TurnSnapshot;
import com.agentos.core.session.TurnStore;
import com.agentos.core.turn.TurnExecutionBudget;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

public class JpaTurnStore implements TurnStore {
    private final SessionRepository sessions;
    private final AgentTurnRepository turns;
    private final SessionMessageRepository messages;
    private final JdbcTemplate jdbc;

    public JpaTurnStore(
            SessionRepository sessions,
            AgentTurnRepository turns,
            SessionMessageRepository messages) {
        this(sessions, turns, messages, null);
    }

    public JpaTurnStore(
            SessionRepository sessions,
            AgentTurnRepository turns,
            SessionMessageRepository messages,
            JdbcTemplate jdbc) {
        this.sessions = sessions;
        this.turns = turns;
        this.messages = messages;
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public TurnSnapshot startTurn(
            UUID turnId,
            SessionKey key,
            String trigger,
            String userMessage,
            TurnExecutionBudget budget) {
        // 先确保唯一 active 行存在，再在同一短事务中加行锁；只有拿到锁后才读取数据库时钟。
        sessions.insertActiveIfAbsent(
                UUID.randomUUID(), key.profileName(), key.channel(), key.userId(), Instant.now());
        SessionEntity session =
                sessions.findActiveForUpdate(key.channel(), key.userId(), key.profileName())
                        .orElseThrow(
                                () -> new AgentOSException(ErrorCode.INTERNAL_ERROR, "无法创建会话"));
        Instant now = databaseNow();
        if (session.getLeaseUntil() != null && session.getLeaseUntil().isAfter(now)) {
            throw new AgentOSException(ErrorCode.SESSION_BUSY, "会话正有轮次在执行");
        }
        String owner = turnId.toString();
        Instant until = now.plus(max(Duration.ofSeconds(150), budget.remaining().plusSeconds(30)));
        session.lease(owner, until);
        session.touch(now);
        sessions.save(session);
        turns.save(
                new AgentTurnEntity(
                        turnId, session.getSessionId(), trigger, now, budget.deadline()));
        messages.save(
                new SessionMessageEntity(
                        session.getSessionId(),
                        turnId,
                        0,
                        "USER",
                        userMessage,
                        null,
                        null,
                        java.util.Map.of(),
                        now));
        return new TurnSnapshot(
                turnId, session.getSessionId(), key, owner, budget.deadline(), "RUNNING");
    }

    @Override
    @Transactional
    public boolean renewLease(TurnSnapshot turn, TurnExecutionBudget budget) {
        SessionEntity session = sessions.findByIdForUpdate(turn.sessionId()).orElse(null);
        Instant now = databaseNow();
        if (session == null
                || !turn.owner().equals(session.getLeaseOwner())
                || session.getLeaseUntil() == null
                || !session.getLeaseUntil().isAfter(now)) {
            return false;
        }
        session.lease(
                turn.owner(),
                now.plus(max(Duration.ofSeconds(150), budget.remaining().plusSeconds(30))));
        sessions.save(session);
        return true;
    }

    @Override
    @Transactional
    public FinishResult finishAndRelease(
            TurnSnapshot turn, String status, String text, String errorMessage) {
        Instant now = databaseNow();
        AgentTurnEntity entity = turns.findById(turn.turnId()).orElse(null);
        SessionEntity session = sessions.findById(turn.sessionId()).orElse(null);
        if (entity == null || session == null) {
            return new FinishResult(status, false, text, errorMessage);
        }
        entity.finish(status, text, errorMessage, now);
        turns.save(entity);
        if (turn.owner().equals(session.getLeaseOwner())) {
            session.lease(null, null);
            sessions.save(session);
        }
        return new FinishResult(status, true, text, errorMessage);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TurnSnapshot> find(UUID turnId) {
        return turns.findById(turnId)
                .flatMap(t ->
                        sessions.findById(t.getSessionId())
                                .map(s ->
                                        new TurnSnapshot(
                                                t.getTurnId(),
                                                t.getSessionId(),
                                                new SessionKey(
                                                        s.getChannel(),
                                                        s.getUserId(),
                                                        s.getProfileName()),
                                                s.getLeaseOwner(),
                                                t.getDeadlineAt(),
                                                t.getStatus())));
    }

    @Override
    @Transactional
    public int reapInterrupted() {
        Instant now = databaseNow();
        int count = 0;
        for (AgentTurnEntity turn : turns.findByStatus("RUNNING")) {
            SessionEntity session = sessions.findById(turn.getSessionId()).orElse(null);
            if (session == null
                    || session.getLeaseUntil() == null
                    || !session.getLeaseUntil().isAfter(now)
                    || !turn.getTurnId().toString().equals(session.getLeaseOwner())) {
                turn.interrupt("启动收尾回收旧轮次", now);
                turns.save(turn);
                count++;
            }
        }
        return count;
    }

    private Instant databaseNow() {
        if (jdbc == null) {
            return Instant.now();
        }
        Instant value =
                jdbc.queryForObject(
                        "select clock_timestamp()", (rs, rowNum) -> rs.getTimestamp(1).toInstant());
        return value == null ? Instant.now() : value;
    }

    private static Duration max(Duration a, Duration b) {
        return a.compareTo(b) >= 0 ? a : b;
    }
}
