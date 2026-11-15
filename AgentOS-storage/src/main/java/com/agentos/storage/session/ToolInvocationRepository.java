package com.agentos.storage.session;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ToolInvocationRepository extends JpaRepository<ToolInvocationEntity, Long> {
    Optional<ToolInvocationEntity> findByTurnIdAndIterationAndToolCallId(
            UUID turnId, int iteration, String toolCallId);

    List<ToolInvocationEntity> findByTurnIdAndStatus(UUID turnId, String status);
}
