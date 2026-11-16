package com.agentos.storage.session;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** 消息表访问端口的 JPA 代理；一条消息一行。 */
public interface SessionMessageRepository extends JpaRepository<SessionMessageEntity, Long> {
    /** 按自增 id 升序读整个会话，供消息存储层分轮；当前尚未在 SQL 中限制轮数。 */
    List<SessionMessageEntity> findBySessionIdOrderByIdAsc(java.util.UUID sessionId);
}
