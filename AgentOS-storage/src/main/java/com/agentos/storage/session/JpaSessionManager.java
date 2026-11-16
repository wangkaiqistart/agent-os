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
 * <p>当前主链路通过 TurnStore 创建会话，本类不负责模型调用，也不自动为每条消息归档旧会话。
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
        // 当前查询没有 FOR UPDATE，事务注解本身不会把“查询后判断”变成并发互斥。
        SessionEntity entity =
                sessions.findByChannelAndUserIdAndProfileNameAndStatus(
                                key.channel(), key.userId(), key.profileName(), "active")
                        .orElse(null);
        if (entity == null) {
            return false;
        }
        Instant now = databaseNow();
        // 有效租约代表仍有轮次占用，不能把它当作空闲会话归档。
        if (entity.getLeaseUntil() != null && entity.getLeaseUntil().isAfter(now)) {
            throw new AgentOSException(ErrorCode.SESSION_BUSY, "上一条消息还在处理");
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
