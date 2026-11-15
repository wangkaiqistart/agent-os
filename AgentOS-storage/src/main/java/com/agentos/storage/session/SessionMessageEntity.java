package com.agentos.storage.session;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "session_messages")
public class SessionMessageEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "turn_id", nullable = false)
    private UUID turnId;

    @Column(nullable = false)
    private int iteration;

    @Column(nullable = false)
    private String type;

    @Column(columnDefinition = "text")
    private String content;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tool_calls", columnDefinition = "jsonb")
    private Object toolCalls;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tool_responses", columnDefinition = "jsonb")
    private Object toolResponses;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SessionMessageEntity() {}

    public SessionMessageEntity(
            UUID sessionId,
            UUID turnId,
            int iteration,
            String type,
            String content,
            Object toolCalls,
            Object toolResponses,
            Map<String, Object> metadata,
            Instant createdAt) {
        this.sessionId = sessionId;
        this.turnId = turnId;
        this.iteration = iteration;
        this.type = type;
        this.content = content;
        this.toolCalls = toolCalls;
        this.toolResponses = toolResponses;
        this.metadata = metadata;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public UUID getTurnId() {
        return turnId;
    }

    public int getIteration() {
        return iteration;
    }

    public String getType() {
        return type;
    }

    public String getContent() {
        return content;
    }

    public Object getToolCalls() {
        return toolCalls;
    }

    public Object getToolResponses() {
        return toolResponses;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
