package com.agentos.storage.session;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentTurnRepository extends JpaRepository<AgentTurnEntity, UUID> {
    List<AgentTurnEntity> findByStatus(String status);
}
