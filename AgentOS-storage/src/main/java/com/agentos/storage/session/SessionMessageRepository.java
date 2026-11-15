package com.agentos.storage.session;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SessionMessageRepository extends JpaRepository<SessionMessageEntity, Long> {
    List<SessionMessageEntity> findBySessionIdOrderByIdAsc(java.util.UUID sessionId);
}
