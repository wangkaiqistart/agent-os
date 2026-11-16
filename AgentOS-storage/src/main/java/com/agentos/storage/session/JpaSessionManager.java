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

    /** 供不需要数据库时钟的隔离测试使用；正式装配注入 JdbcTemplate。 */
    public JpaSessionManager(SessionRepository sessions) {
        this(sessions, null);
    }

    public JpaSessionManager(SessionRepository sessions, JdbcTemplate jdbc) {
        this.sessions = sessions;
        this.jdbc = jdbc;
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
    @Transactional
    public boolean archiveIfIdle(SessionKey key) {
        // 条件更新在数据库内原子判断租约并写入归档时间，避免“先读后写”的竞争窗口。
        if (jdbc != null
                && sessions.archiveActiveIfIdle(key.channel(), key.userId(), key.profileName())
                        > 0) {
            return true;
        }
        // 未归档时只读取当前状态用于区分“没有会话”和“仍被占用”；不再根据读取结果写回。
        SessionEntity entity =
                sessions.findByChannelAndUserIdAndProfileNameAndStatus(
                                key.channel(), key.userId(), key.profileName(), "active")
                        .orElse(null);
        if (entity == null) {
            return false;
        }
        Instant now = databaseNow();
        if (entity.getLeaseUntil() != null && entity.getLeaseUntil().isAfter(now)) {
            throw new AgentOSException(ErrorCode.SESSION_BUSY, "上一条消息还在处理");
        }
        if (jdbc != null) {
            // 正式路径只接受上面的条件更新结果，避免这里的读取再次打开写入竞争窗口。
            return false;
        }
        // 没有 JdbcTemplate 的隔离构造器仍保持可测试，但正式装配不会走这里。
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
