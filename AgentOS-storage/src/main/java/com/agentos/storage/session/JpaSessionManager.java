package com.agentos.storage.session;

import com.agentos.core.error.AgentOSException;
import com.agentos.core.error.ErrorCode;
import com.agentos.core.session.SessionKey;
import com.agentos.core.session.SessionManager;
import com.agentos.core.session.SessionSnapshot;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 会话查询与归档适配器，按 channel + userId + profileName 查找活跃会话。
 *
 * <p>主链路通过 TurnStore 创建会话，本类不负责模型调用；CLI --new 通过本类原子归档空闲会话。
 */
public class JpaSessionManager implements SessionManager {
    private final SessionRepository sessions;
    private final JdbcTemplate jdbc;
    private final JpaTurnStore turnStore;

    /** 供不需要数据库时钟的隔离测试使用；正式装配注入 JdbcTemplate。 */
    public JpaSessionManager(SessionRepository sessions) {
        this(sessions, null);
    }

    public JpaSessionManager(SessionRepository sessions, JdbcTemplate jdbc) {
        this(sessions, jdbc, null);
    }

    public JpaSessionManager(
            SessionRepository sessions, JdbcTemplate jdbc, JpaTurnStore turnStore) {
        this.sessions = sessions;
        this.jdbc = jdbc;
        this.turnStore = turnStore;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SessionSnapshot> findActive(SessionKey key) {
        // 事务内读取实体，再转换为值快照；离开事务后调用方不依赖 JPA 懒加载。
        return sessions.findByChannelAndUserIdAndProfileNameAndStatus(
                        key.channel(), key.userId(), key.profileName(), "active")
                .map(entity ->
                        new SessionSnapshot(
                                entity.getSessionId(),
                                key,
                                entity.getStatus(),
                                entity.getLeaseOwner(),
                                entity.getLeaseUntil()));
    }

    @Override
    public boolean archiveIfIdle(SessionKey key) {
        // 先进入有界短事务再锁行；不能让外层事务持有连接等待另一个线程。
        return turnStore == null ? archive(key) : turnStore.storageOperation(() -> archive(key));
    }

    private boolean archive(SessionKey key) {
        // 与起步/续租共用会话行锁，锁后只捕获一次数据库时间，避免等锁期间使用旧时刻。
        SessionEntity entity =
                sessions.findActiveForUpdate(key.channel(), key.userId(), key.profileName())
                        .orElse(null);
        if (entity == null) {
            return false;
        }
        Instant now = databaseNow();
        if (entity.getLeaseUntil() != null && entity.getLeaseUntil().isAfter(now)) {
            throw new AgentOSException(ErrorCode.SESSION_BUSY, "上一条消息还在处理");
        }
        // 失效 owner 的父轮次/工具先在同一事务恢复，正常历史保持原样。
        if (turnStore != null) {
            turnStore.recoverSession(entity, now);
        }
        entity.archive(now);
        sessions.save(entity);
        return true;
    }

    private Instant databaseNow() {
        // 生产路径使用数据库时钟，减少多个应用实例本地时钟偏差的影响。
        if (jdbc == null) {
            return Instant.now();
        }
        Instant value =
                jdbc.queryForObject(
                        "select clock_timestamp()", (rs, rowNum) -> rs.getTimestamp(1).toInstant());
        return value == null ? Instant.now() : value;
    }
}
