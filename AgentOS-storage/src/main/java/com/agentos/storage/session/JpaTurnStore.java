package com.agentos.storage.session;

import com.agentos.core.config.TurnProperties;
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

/**
 * 轮次存储的 PostgreSQL 实现：用会话行锁协调抢占，用限时租约表示谁正在执行本轮。
 *
 * <p>行锁只持有到短事务结束，模型等待期间靠 leaseOwner/leaseUntil 协调，而不是一直锁表。
 * 本类由 StorageAutoConfiguration 注册，调用方经 Spring 事务代理进入下面的方法。
 */
public class JpaTurnStore implements TurnStore {
    private final SessionRepository sessions;
    private final AgentTurnRepository turns;
    private final SessionMessageRepository messages;
    private final JdbcTemplate jdbc;
    private final TurnProperties properties;

    /** 简化测试的构造器；未注入 JDBC 时以进程时间代替数据库时间。 */
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
        this(sessions, turns, messages, jdbc, new TurnProperties(null, null, null));
    }

    public JpaTurnStore(
            SessionRepository sessions,
            AgentTurnRepository turns,
            SessionMessageRepository messages,
            JdbcTemplate jdbc,
            TurnProperties properties) {
        this.sessions = sessions;
        this.turns = turns;
        this.messages = messages;
        this.jdbc = jdbc;
        this.properties = properties;
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
        // 锁内检查有效租约，避免两个并发请求都以为会话空闲。过期租约允许被新轮次接管。
        if (session.getLeaseUntil() != null && session.getLeaseUntil().isAfter(now)) {
            throw new AgentOSException(ErrorCode.SESSION_BUSY, "会话正有轮次在执行");
        }
        String owner = turnId.toString();
        Instant until =
                now.plus(
                        max(
                                properties.leaseTtl(),
                                budget.remaining().plus(properties.leaseGrace())));
        session.lease(owner, until);
        session.touch(now);
        sessions.save(session);
        turns.save(
                new AgentTurnEntity(
                        turnId, session.getSessionId(), trigger, now, budget.deadline()));
        // iteration=0 表示尚未调用模型的用户输入，与 RUNNING 轮次、租约一起提交或一起回滚。
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
        // 续租也先锁行；旧 owner 或已经过期的 owner 不允许通过续租“复活”。
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
                now.plus(
                        max(
                                properties.leaseTtl(),
                                budget.remaining().plus(properties.leaseGrace()))));
        sessions.save(session);
        return true;
    }

    @Override
    @Transactional
    public FinishResult finishAndRelease(
            TurnSnapshot turn, String status, String text, String errorMessage) {
        return finish(turn, status, text, errorMessage, null);
    }

    @Override
    @Transactional
    public FinishResult finishAndRelease(
            TurnSnapshot turn, String status, String text, String errorMessage, int iterations) {
        return finish(turn, status, text, errorMessage, iterations);
    }

    private FinishResult finish(
            TurnSnapshot turn,
            String status,
            String text,
            String errorMessage,
            Integer iterations) {
        // 收尾独立于模型调用。当前是普通查询，没有使用 startTurn 那样的 FOR UPDATE 行锁。
        Instant now = databaseNow();
        AgentTurnEntity entity = turns.findById(turn.turnId()).orElse(null);
        SessionEntity session = sessions.findById(turn.sessionId()).orElse(null);
        if (entity == null || session == null) {
            // 返回未确认，交上层区分“业务结果已得到”和“数据库终态已确认”。
            return new FinishResult(status, false, text, errorMessage);
        }
        // 来自循环结果，不靠文本或审计行数推测；与终态一起在本次短事务中保存。
        if (iterations != null) {
            entity.iteration(iterations);
        }
        entity.finish(status, text, errorMessage, now);
        turns.save(entity);
        // 若租约已被别的轮次接管，不清除新 owner 的租约。
        if (turn.owner().equals(session.getLeaseOwner())) {
            session.lease(null, null);
            sessions.save(session);
        }
        return new FinishResult(status, true, text, errorMessage);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TurnSnapshot> find(UUID turnId) {
        // 只返回跨模块快照，不把受持久化上下文管理的实体暴露给 core。
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
        // 这是回收操作本身，尚无定时器自动调用；只处理租约失效或已换 owner 的 RUNNING 轮次。
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
                // clock_timestamp() 是查询执行时刻；不同实例以同一数据库时钟判断租约。
                jdbc.queryForObject(
                        "select clock_timestamp()", (rs, rowNum) -> rs.getTimestamp(1).toInstant());
        return value == null ? Instant.now() : value;
    }

    private static Duration max(Duration a, Duration b) {
        return a.compareTo(b) >= 0 ? a : b;
    }
}
