package com.agentos.core.session;

import java.util.Optional;

/** 会话查询与归档端口。 */
public interface SessionManager {
    Optional<SessionSnapshot> findActive(SessionKey key);

    boolean archiveIfIdle(SessionKey key);
}
