package com.agentos.storage.session;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** 消息表访问端口的 JPA 代理；一条消息一行。 */
public interface SessionMessageRepository extends JpaRepository<SessionMessageEntity, Long> {
    /** 按自增 id 升序读取消息；存储层随后按已选轮次过滤，避免直接按消息行截断。 */
    List<SessionMessageEntity> findBySessionIdOrderByIdAsc(java.util.UUID sessionId);
}
