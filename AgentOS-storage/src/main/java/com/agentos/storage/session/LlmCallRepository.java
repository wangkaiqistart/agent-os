package com.agentos.storage.session;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LlmCallRepository extends JpaRepository<LlmCallEntity, Long> {
    Optional<LlmCallEntity> findByWriteId(UUID writeId);
}
