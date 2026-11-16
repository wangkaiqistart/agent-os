package com.agentos.core.session;

import java.util.Optional;

/** 会话查询与归档端口；Core 不暴露 JPA 实体，具体实现由 Storage 提供。 */
public interface SessionManager {
    /** 按自然键查找 active 会话；不存在时返回空，不在此处隐式创建。 */
    Optional<SessionSnapshot> findActive(SessionKey key);

    /** 空闲时归档；没有活跃会话返回 false，有有效租约时实现可抛 SESSION_BUSY。 */
    boolean archiveIfIdle(SessionKey key);
}
