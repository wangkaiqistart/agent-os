package com.agentos.core.support;

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
        return sessions.computeIfPresent(
                        key,
                        (k, v) ->
                                v.leaseUntil() != null && v.leaseUntil().isAfter(Instant.now())
                                        ? v
                                        : new SessionSnapshot(
                                                v.sessionId(), k, "archived", null, null))
                != null;
    }

    public SessionSnapshot create(SessionKey key) {
        return sessions.computeIfAbsent(
                key, k -> new SessionSnapshot(UUID.randomUUID(), k, "active", null, null));
    }
}
