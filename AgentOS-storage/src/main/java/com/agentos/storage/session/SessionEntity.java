package com.agentos.storage.session;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sessions")
public class SessionEntity {
    @Id
    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "profile_name", nullable = false)
    private String profileName;

    @Column(nullable = false)
    private String channel;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(nullable = false)
    private String status = "active";

    @Column(name = "lease_owner")
    private String leaseOwner;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_active_at", nullable = false)
    private Instant lastActiveAt;

    @Column(name = "archived_at")
    private Instant archivedAt;

    protected SessionEntity() {}

    public SessionEntity(
            UUID sessionId, String profileName, String channel, String userId, Instant now) {
        this.sessionId = sessionId;
        this.profileName = profileName;
        this.channel = channel;
        this.userId = userId;
        this.createdAt = now;
        this.lastActiveAt = now;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public String getProfileName() {
        return profileName;
    }

    public String getChannel() {
        return channel;
    }

    public String getUserId() {
        return userId;
    }

    public String getStatus() {
        return status;
    }

    public String getLeaseOwner() {
        return leaseOwner;
    }

    public Instant getLeaseUntil() {
        return leaseUntil;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastActiveAt() {
        return lastActiveAt;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    public void lease(String owner, Instant until) {
        leaseOwner = owner;
        leaseUntil = until;
    }

    public void archive(Instant at) {
        status = "archived";
        archivedAt = at;
        leaseOwner = null;
        leaseUntil = null;
    }

    public void touch(Instant at) {
        lastActiveAt = at;
    }
}
