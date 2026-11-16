package com.agentos.storage.audit;

import com.agentos.core.audit.AuditWriteException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 一次审计尝试一个 REQUIRES_NEW 短事务。SQL、行锁及 socket 等待都有上限，
 * 且获取连接后的迟到工作不能越过本次尝试的截止时间开始写入。
 * 退避由 core 执行，不在本类持有连接期间等待。
 */
public final class AuditTransactions {
    private final PlatformTransactionManager manager;
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;

    public AuditTransactions(PlatformTransactionManager manager, DataSource dataSource) {
        this.manager = manager;
        this.dataSource = dataSource;
        jdbc = new JdbcTemplate(dataSource);
    }

    public <T> T execute(Duration timeout, Supplier<T> action) {
        long deadline = System.nanoTime() + timeout.toNanos();
        var transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // Spring 只能表示整秒，向上取整；毫秒级的真实预算由下方 PostgreSQL/socket 及提交检查守住。
        // 普通存储/启动恢复使用各自操作预算；审计最多250ms，deadline后仍共用一秒清理窗口。
        transaction.setTimeout(Math.toIntExact(Math.max(1, (timeout.toMillis() + 999) / 1000)));
        return transaction.execute(status -> {
            long millis = Math.max(1, Duration.ofNanos(deadline - System.nanoTime()).toMillis());
            if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) {
                throw new AuditWriteException("审计尝试已过期");
            }
            Connection connection = DataSourceUtils.getConnection(dataSource);
            try {
                int original = connection.getNetworkTimeout();
                connection.setNetworkTimeout(Runnable::run, Math.toIntExact(millis));
                // afterCompletion 在提交/回滚完成后、连接归还池之前恢复，不把超时设置泄漏到后续借用者。
                org.springframework.transaction.support.TransactionSynchronizationManager
                        .registerSynchronization(
                                new org.springframework.transaction.support
                                        .TransactionSynchronization() {
                                    @Override
                                    public void beforeCommit(boolean readOnly) {
                                        if (Thread.currentThread().isInterrupted()
                                                || System.nanoTime() >= deadline) {
                                            throw new AuditWriteException("审计提交预算耗尽");
                                        }
                                        try {
                                            connection.setNetworkTimeout(
                                                    Runnable::run,
                                                    Math.toIntExact(
                                                            Math.max(
                                                                    1,
                                                                    Duration.ofNanos(
                                                                                    deadline
                                                                                            - System
                                                                                                    .nanoTime())
                                                                            .toMillis())));
                                        } catch (SQLException failure) {
                                            throw new AuditWriteException("审计提交 I/O 时限不可用");
                                        }
                                    }

                                    @Override
                                    public void afterCompletion(int completionStatus) {
                                        try {
                                            if (!connection.isClosed()) {
                                                connection.setNetworkTimeout(
                                                        Runnable::run, original);
                                            }
                                        } catch (SQLException failure) {
                                            /* 已断开的连接由连接池淘汰，不输出原始驱动异常。 */
                                        }
                                    }
                                });
                // 两个事务局部设置一次往返完成；远程数据库上不能让设置本身吃掉审计预算。
                jdbc.queryForMap(
                        "select set_config('statement_timeout', ?, true) as statement_timeout, "
                                + "set_config('lock_timeout', ?, true) as lock_timeout",
                        millis + "ms",
                        Math.max(1, millis / 2) + "ms");
                return action.get();
            } catch (SQLException failure) {
                throw new AuditWriteException("无法设置审计 I/O 时限");
            } finally {
                DataSourceUtils.releaseConnection(connection, dataSource);
            }
        });
    }

    /** 先锁会话再重新检查父轮次，避免接管/回收之后插入新的 in_progress 孤儿。 */
    public void requireRunning(java.util.UUID sessionId, java.util.UUID turnId) {
        Integer count =
                jdbc.queryForObject(
                        // 两个 MATERIALIZED 边界保证先取会话锁，再读取父轮次并检查当前数据库时间。
                        // 不把 clock_timestamp 提前到等锁之前；同一往返内保留 session → turn 的顺序。
                        """
                        with locked_session as materialized (
                            select session_id, lease_owner, lease_until from sessions
                            where session_id=? for update
                        ), parent as materialized (
                            select s.lease_owner, s.lease_until, t.status, t.deadline_at
                            from locked_session s join agent_turns t on t.session_id=s.session_id
                            where t.turn_id=?
                        )
                        select count(*) from parent where status='RUNNING' and lease_owner=?
                            and lease_until>clock_timestamp() and deadline_at>clock_timestamp()
                        """,
                        Integer.class,
                        sessionId,
                        turnId,
                        turnId.toString());
        if (count == null || count != 1) {
            throw new AuditWriteException("工具审计所属轮次已失效");
        }
    }

    /** 只重试数据库明确报告的临时失败；数据约束、非法输入与未知提交不在此列。 */
    public static boolean transientFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLException sql) {
                String state = sql.getSQLState();
                if (state != null && state.startsWith("08")) {
                    return false;
                } // 连接断开可能已提交，保守查证。
                if (state != null
                        && (state.startsWith("40")
                                || state.equals("55P03")
                                || state.equals("57014"))) {
                    return true;
                }
            }
        }
        return failure instanceof TransientDataAccessException;
    }
}
