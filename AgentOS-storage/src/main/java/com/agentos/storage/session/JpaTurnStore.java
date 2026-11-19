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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 轮次存储的 PostgreSQL 实现：用会话行锁协调抢占，用限时租约表示谁正在执行本轮。
 *
 * <p>行锁只持有到短事务结束，模型等待期间靠 leaseOwner/leaseUntil 协调，而不是一直锁表。
 * 本类由 StorageAutoConfiguration 注册，通过 AuditTransactions 模板执行有界短事务。
 */
public class JpaTurnStore implements TurnStore {
    // 启动扫描不属于任何单轮：整次最多 30 秒，每次查询/会话恢复最多 5 秒。
    // 启动不占用任何单轮预算，也不改变 deadline 后共享的一秒清理窗口。
    private static final Duration RECOVERY_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration RECOVERY_TRANSACTION_TIMEOUT = Duration.ofSeconds(5);
    private final SessionRepository sessions;
    private final AgentTurnRepository turns;
    private final SessionMessageRepository messages;
    private final JdbcTemplate jdbc;
    private final TurnProperties properties;
    private com.agentos.storage.audit.AuditTransactions transactions;
    private com.agentos.core.turn.TurnBudgetRegistry budgets;

    public JpaTurnStore(
            SessionRepository sessions,
            AgentTurnRepository turns,
            SessionMessageRepository messages,
            JdbcTemplate jdbc,
            TurnProperties properties,
            com.agentos.storage.audit.AuditTransactions transactions,
            com.agentos.core.turn.TurnBudgetRegistry budgets) {
        this(sessions, turns, messages, jdbc, properties);
        this.transactions = transactions;
        this.budgets = budgets;
    }

    /** --new 没有轮次预算，仍采用普通存储操作上限约束连接、锁及提交。 */
    <T> T storageOperation(java.util.function.Supplier<T> action) {
        return bounded(properties.storageTimeout(), action);
    }

    private <T> T bounded(Duration timeout, java.util.function.Supplier<T> action) {
        if (timeout.isZero() || timeout.isNegative()) {
            throw new com.agentos.core.turn.TurnDeadlineExceededException();
        }
        if (transactions == null) {
            return action.get();
        }
        Duration limit =
                timeout.compareTo(properties.storageTimeout()) > 0
                        ? properties.storageTimeout()
                        : timeout;
        long deadline = System.nanoTime() + limit.toNanos();
        var task =
                new FutureTask<T>(() -> {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0 || Thread.currentThread().isInterrupted()) {
                        throw new IllegalStateException("轮次存储操作预算已耗尽");
                    }
                    return transactions.execute(Duration.ofNanos(remaining), action);
                });
        Thread.ofVirtual().name("turn-storage").start(task);
        try {
            // 数据库事务尚未开始时也可能卡在连接池；外层等待必须采用相同操作预算。
            return task.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("轮次存储操作被中断，提交结果须查证");
        } catch (TimeoutException failure) {
            throw new IllegalStateException("轮次存储操作超时，提交结果须查证");
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("轮次存储操作失败，提交结果须查证");
        } finally {
            // 取消不能证明事务回滚；AuditTransactions 在迟到取连接后及提交前再次检查预算/中断。
            task.cancel(true);
        }
    }

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
    public TurnSnapshot startTurn(
            UUID turnId,
            SessionKey key,
            String trigger,
            String userMessage,
            TurnExecutionBudget budget) {
        return bounded(budget.remaining(), () -> start(turnId, key, trigger, userMessage, budget));
    }

    private TurnSnapshot start(
            UUID turnId,
            SessionKey key,
            String trigger,
            String userMessage,
            TurnExecutionBudget budget) {
        // 先确保唯一 active 行存在，再在同一短事务中加行锁；只有拿到锁后才读取数据库时钟。
        SessionEntity session;
        do {
            if (budget.expired() || budget.cancelled()) {
                throw new com.agentos.core.turn.TurnDeadlineExceededException();
            }
            sessions.insertActiveIfAbsent(
                    UUID.randomUUID(),
                    key.profileName(),
                    key.channel(),
                    key.userId(),
                    Instant.now());
            session =
                    sessions.findActiveForUpdate(key.channel(), key.userId(), key.profileName())
                            .orElse(null);
            // --new 可在冲突插入与锁查询之间归档旧行；只重新查找/创建会话，不重放轮次或消息。
        } while (session == null);
        Instant now = databaseNow();
        // 锁内检查有效租约，避免两个并发请求都以为会话空闲。过期租约允许被新轮次接管。
        if (session.getLeaseUntil() != null && session.getLeaseUntil().isAfter(now)) {
            throw new AgentOSException(ErrorCode.SESSION_BUSY, "上一条消息还在处理");
        }
        recoverSession(session, now);
        if (budget.expired() || budget.cancelled()) {
            throw new com.agentos.core.turn.TurnDeadlineExceededException();
        }
        String owner = turnId.toString();
        Instant deadline = now.plus(budget.remaining());
        Instant until =
                later(now.plus(properties.leaseTtl()), deadline.plus(properties.leaseGrace()));
        session.lease(owner, until);
        session.touch(now);
        sessions.save(session);
        // 数据库时间加单调预算余量；不把 Java 墙钟当成数据库时钟。
        turns.save(new AgentTurnEntity(turnId, session.getSessionId(), trigger, now, deadline));
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
        return new TurnSnapshot(turnId, session.getSessionId(), key, owner, deadline, "RUNNING");
    }

    @Override
    public boolean renewLease(TurnSnapshot turn, TurnExecutionBudget budget) {
        return bounded(budget.remaining(), () -> renew(turn, budget));
    }

    private boolean renew(TurnSnapshot turn, TurnExecutionBudget budget) {
        // 续租也先锁行；旧 owner 或已经过期的 owner 不允许通过续租“复活”。
        SessionEntity session = sessions.findByIdForUpdate(turn.sessionId()).orElse(null);
        AgentTurnEntity parent = turns.findById(turn.turnId()).orElse(null);
        Instant now = databaseNow();
        if (budget.expired()) {
            throw new com.agentos.core.turn.TurnDeadlineExceededException();
        }
        // 等待人工授权时进程内预算暂停、截止时间后移；同步到库内截止时间，否则会被误判为到期。
        // 从未暂停的轮次不改库内截止时间，库内到期仍是独立的终止依据。
        boolean extended =
                parent != null
                        && budget.pausedNanos() > 0
                        && "RUNNING".equals(parent.getStatus())
                        && parent.extendDeadline(now.plus(budget.remaining()));
        if (parent != null && !parent.getDeadlineAt().isAfter(now)) {
            throw new com.agentos.core.turn.TurnDeadlineExceededException();
        }
        if (session == null
                || !"active".equals(session.getStatus())
                || parent == null
                || !parent.getSessionId().equals(turn.sessionId())
                || !"RUNNING".equals(parent.getStatus())
                || budget.cancelled()
                || !turn.owner().equals(session.getLeaseOwner())
                || session.getLeaseUntil() == null
                || !session.getLeaseUntil().isAfter(now)) {
            return false;
        }
        session.lease(
                turn.owner(),
                later(
                        now.plus(properties.leaseTtl()),
                        parent.getDeadlineAt().plus(properties.leaseGrace())));
        sessions.save(session);
        if (extended) {
            turns.save(parent);
        }
        return true;
    }

    @Override
    public FinishResult finishAndRelease(
            TurnSnapshot turn, String status, String text, String errorMessage) {
        return finishBounded(turn, status, text, errorMessage, null);
    }

    @Override
    public FinishResult finishAndRelease(
            TurnSnapshot turn, String status, String text, String errorMessage, int iterations) {
        return finishBounded(turn, status, text, errorMessage, iterations);
    }

    private FinishResult finishBounded(
            TurnSnapshot turn,
            String status,
            String text,
            String errorMessage,
            Integer iterations) {
        var budget = budgets == null ? null : budgets.get(turn.turnId());
        Duration remaining =
                budget == null
                        ? properties.storageTimeout()
                        : Duration.ofNanos(budget.remainingHardStopNanos());
        return bounded(remaining, () -> finish(turn, status, text, errorMessage, iterations));
    }

    private FinishResult finish(
            TurnSnapshot turn,
            String status,
            String text,
            String errorMessage,
            Integer iterations) {
        // 所有路径遵守 session → turn 的锁顺序，旧 owner 不能覆盖接管后的终态。
        SessionEntity session = sessions.findByIdForUpdate(turn.sessionId()).orElse(null);
        AgentTurnEntity entity = turns.findById(turn.turnId()).orElse(null);
        Instant now = databaseNow();
        if (entity == null || session == null) {
            // 返回未确认，交上层区分“业务结果已得到”和“数据库终态已确认”。
            return new FinishResult(status, false, text, errorMessage);
        }
        if (!"RUNNING".equals(entity.getStatus())) {
            interruptTools(entity.getTurnId(), now);
            if (turn.owner().equals(session.getLeaseOwner())) {
                session.lease(null, null);
            }
            return new FinishResult(
                    entity.getStatus(), true, entity.getFinalText(), entity.getErrorMessage());
        }
        if (!turn.owner().equals(session.getLeaseOwner())
                || session.getLeaseUntil() == null
                || !session.getLeaseUntil().isAfter(now)
                || !"active".equals(session.getStatus())) {
            // 仅仍 RUNNING 的本轮收口可追加固定警告；上方已有终态始终优先。
            // 不接受失效 owner 的任意答案，也不清除接管者的租约。
            var budget = budgets == null ? null : budgets.get(turn.turnId());
            entity.finish(
                    "INTERRUPTED",
                    budget == null ? "本轮已中断" : budget.submissionAwareText("本轮已中断"),
                    "会话租约已失效",
                    now);
            if (turn.owner().equals(session.getLeaseOwner())) {
                session.lease(null, null);
            }
            interruptTools(entity.getTurnId(), now);
            return new FinishResult(
                    entity.getStatus(), true, entity.getFinalText(), entity.getErrorMessage());
        }
        interruptTools(entity.getTurnId(), now);
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
    public Optional<TurnSnapshot> find(UUID turnId) {
        return bounded(properties.storageTimeout(), () -> findSnapshot(turnId));
    }

    @Override
    public Optional<TurnSnapshot> find(UUID turnId, TurnExecutionBudget budget) {
        // 查证可能发生在起步失败之后；不能借独立查询重新获得五秒或整轮执行预算。
        return bounded(
                Duration.ofNanos(budget.remainingHardStopNanos()), () -> findSnapshot(turnId));
    }

    private Optional<TurnSnapshot> findSnapshot(UUID turnId) {
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
                                                t.getTurnId().toString(),
                                                t.getDeadlineAt(),
                                                t.getStatus())));
    }

    @Override
    public int reapInterrupted() {
        long deadline = System.nanoTime() + RECOVERY_TIMEOUT.toNanos();
        var task = new FutureTask<Integer>(() -> reap(deadline));
        Thread.ofVirtual().name("turn-recovery").start(task);
        try {
            // 连接获取也扣总预算；Future 取消之后，事务入口及提交检查禁止迟到写入。
            return task.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("启动回收被中断，未确认全部恢复完成");
        } catch (TimeoutException failure) {
            throw new IllegalStateException("启动回收超过 30 秒，未确认全部恢复完成");
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("启动回收失败，未确认全部恢复完成");
        } finally {
            task.cancel(true);
        }
    }

    private <T> T recoveryTransaction(long deadline, java.util.function.Supplier<T> action) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0 || Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("启动回收预算已耗尽");
        }
        Duration timeout =
                Duration.ofNanos(Math.min(remaining, RECOVERY_TRANSACTION_TIMEOUT.toNanos()));
        return transactions == null ? action.get() : transactions.execute(timeout, action);
    }

    private int reap(long deadline) {
        int count = 0;
        var candidates = recoveryTransaction(deadline, sessions::findRecoveryCandidateIds);
        for (UUID sessionId : candidates) {
            // 每个会话独立提交；后续失败不回滚已恢复会话，也不在全量扫描期间持锁。
            count +=
                    recoveryTransaction(
                            deadline,
                            () -> {
                                SessionEntity session =
                                        sessions.findByIdForUpdate(sessionId).orElse(null);
                                return session == null ? 0 : recoverSession(session, databaseNow());
                            });
        }
        return count;
    }

    /** 启动和接管共用：有效 owner 保留；终态孤儿工具也可独立回收。 */
    int recoverSession(SessionEntity session, Instant now) {
        int count = 0;
        for (AgentTurnEntity turn : turns.findRecoveryCandidates(session.getSessionId(), now)) {
            boolean active = "RUNNING".equals(turn.getStatus());
            boolean owns = turn.getTurnId().toString().equals(session.getLeaseOwner());
            boolean live =
                    owns && session.getLeaseUntil() != null && session.getLeaseUntil().isAfter(now);
            if (active && live) {
                continue;
            }
            if (active) {
                turn.interrupt("租约失效，回收旧轮次", now);
                turns.save(turn);
                count++;
                if (owns) {
                    session.lease(null, null);
                }
            }
            if (!active && owns) {
                session.lease(null, null);
                count++;
            }
            count += interruptTools(turn.getTurnId(), now);
        }
        return count;
    }

    private int interruptTools(UUID turnId, Instant now) {
        if (jdbc == null) {
            return 0;
        }
        return jdbc.update(
                "update tool_invocations set status='interrupted', error_message=?, updated_at=? where turn_id=? and status='in_progress'",
                "轮次结束或租约失效，工具执行结果未知",
                java.sql.Timestamp.from(now),
                turnId);
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

    private static Instant later(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }
}
