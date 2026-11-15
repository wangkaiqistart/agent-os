package com.agentos.storage.session;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "tool_invocations")
public class ToolInvocationEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "turn_id", nullable = false)
    private UUID turnId;

    @Column(nullable = false)
    private int iteration;

    @Column(name = "tool_call_id", nullable = false)
    private String toolCallId;

    @Column(name = "tool_name", nullable = false)
    private String toolName;

    @Column(name = "tool_source", nullable = false)
    private String toolSource;

    @Column(nullable = false)
    private String status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_json", columnDefinition = "jsonb")
    private Object inputJson;

    @Column(name = "result_text")
    private String resultText;

    @Column(name = "result_chars")
    private Integer resultChars;

    @Column(name = "result_truncated", nullable = false)
    private boolean resultTruncated;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(nullable = false)
    private boolean idempotent;

    @Column(nullable = false)
    private short attempts;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ToolInvocationEntity() {}

    public ToolInvocationEntity(
            UUID sessionId,
            UUID turnId,
            int iteration,
            String toolCallId,
            String toolName,
            String source,
            Object inputJson,
            boolean idempotent,
            Instant now) {
        this.sessionId = sessionId;
        this.turnId = turnId;
        this.iteration = iteration;
        this.toolCallId = toolCallId;
        this.toolName = toolName;
        toolSource = source;
        this.inputJson = inputJson;
        this.idempotent = idempotent;
        status = "in_progress";
        createdAt = now;
        updatedAt = now;
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

    public String getToolCallId() {
        return toolCallId;
    }

    public String getToolName() {
        return toolName;
    }

    public String getToolSource() {
        return toolSource;
    }

    public String getStatus() {
        return status;
    }

    public Object getInputJson() {
        return inputJson;
    }

    public String getResultText() {
        return resultText;
    }

    public Integer getResultChars() {
        return resultChars;
    }

    public boolean isResultTruncated() {
        return resultTruncated;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public boolean isIdempotent() {
        return idempotent;
    }

    public int getAttempts() {
        return attempts;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void complete(
            String status,
            String resultText,
            Integer resultChars,
            boolean truncated,
            String error,
            int attempts,
            Long duration,
            Instant now) {
        this.status = status;
        this.resultText = resultText;
        this.resultChars = resultChars;
        resultTruncated = truncated;
        errorMessage = error;
        this.attempts = (short) attempts;
        durationMs = duration;
        updatedAt = now;
    }
}
