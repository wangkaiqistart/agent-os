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

public class JpaSessionManager implements SessionManager {
    private final SessionRepository sessions;
    private final JdbcTemplate jdbc;

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
        entity.archive(now);
        sessions.save(entity);
        return true;
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
}
