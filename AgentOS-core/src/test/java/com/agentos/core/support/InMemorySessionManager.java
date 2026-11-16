package com.agentos.core.support;

import com.agentos.core.error.AgentOSException;
import com.agentos.core.error.ErrorCode;
import com.agentos.core.session.SessionKey;
import com.agentos.core.session.SessionManager;
import com.agentos.core.session.SessionSnapshot;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemorySessionManager implements SessionManager {
    private final ConcurrentHashMap<SessionKey, SessionSnapshot> sessions =
            new ConcurrentHashMap<>();

    public Optional<SessionSnapshot> findActive(SessionKey key) {
        return Optional.ofNullable(sessions.get(key));
    }

    public boolean archiveIfIdle(SessionKey key) {
        SessionSnapshot current = sessions.get(key);
        if (current != null
                && current.leaseUntil() != null
                && current.leaseUntil().isAfter(Instant.now())) {
            // 内存适配器也要模拟正式实现的忙会话语义，避免 --new 在测试中误开新轮次。
            throw new AgentOSException(ErrorCode.SESSION_BUSY, "上一条消息还在处理");
        }
        return sessions.computeIfPresent(
                        key,
                        (k, v) -> new SessionSnapshot(v.sessionId(), k, "archived", null, null))
                != null;
    }

    public SessionSnapshot create(SessionKey key) {
        return sessions.compute(
                key,
                (k, existing) ->
                        existing == null || "archived".equals(existing.status())
                                ? new SessionSnapshot(UUID.randomUUID(), k, "active", null, null)
                                : existing);
    }
}
