package com.agentos.storage.session;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SessionRepository extends JpaRepository<SessionEntity, UUID> {
    Optional<SessionEntity> findByChannelAndUserIdAndProfileNameAndStatus(
            String channel, String userId, String profileName, String status);

    @Query(
            value = "SELECT * FROM sessions WHERE session_id = :sessionId FOR UPDATE",
            nativeQuery = true)
    Optional<SessionEntity> findByIdForUpdate(@Param("sessionId") UUID sessionId);

    @Modifying
    @Query(
            value =
                    """
                    INSERT INTO sessions(session_id, profile_name, channel, user_id, status, created_at, last_active_at)
                    VALUES (:id, :profile, :channel, :user, 'active', :at, :at)
                    ON CONFLICT (channel, user_id, profile_name) WHERE status = 'active' DO NOTHING
                    """,
            nativeQuery = true)
    int insertActiveIfAbsent(
            @Param("id") UUID id,
            @Param("profile") String profile,
            @Param("channel") String channel,
            @Param("user") String user,
            @Param("at") java.time.Instant at);

    @Query(
            value =
                    "SELECT * FROM sessions WHERE channel = :channel AND user_id = :user AND profile_name = :profile AND status = 'active' FOR UPDATE",
            nativeQuery = true)
    Optional<SessionEntity> findActiveForUpdate(
            @Param("channel") String channel,
            @Param("user") String user,
            @Param("profile") String profile);
}
