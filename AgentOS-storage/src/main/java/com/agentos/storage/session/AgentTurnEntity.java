package com.agentos.storage.session;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "agent_turns")
public class AgentTurnEntity {
    @Id
    @Column(name = "turn_id", nullable = false)
    private UUID turnId;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(nullable = false)
    private String trigger;

    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private int iterations;

    @Column(name = "final_text")
    private String finalText;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "deadline_at", nullable = false)
    private Instant deadlineAt;

    protected AgentTurnEntity() {}

    public AgentTurnEntity(
            UUID turnId, UUID sessionId, String trigger, Instant startedAt, Instant deadlineAt) {
        this.turnId = turnId;
        this.sessionId = sessionId;
        this.trigger = trigger;
        this.startedAt = startedAt;
        this.deadlineAt = deadlineAt;
        this.status = "RUNNING";
    }

    public UUID getTurnId() {
        return turnId;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public String getTrigger() {
        return trigger;
    }

    public String getStatus() {
        return status;
    }

    public int getIterations() {
        return iterations;
    }

    public String getFinalText() {
        return finalText;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public Instant getDeadlineAt() {
        return deadlineAt;
    }

    public void finish(String status, String text, String error, Instant at) {
        this.status = status;
        finalText = text;
        errorMessage = error;
        finishedAt = at;
    }

    public void iteration(int value) {
        iterations = value;
    }

    public void interrupt(String reason, Instant at) {
        finish("INTERRUPTED", "本轮已中断", reason, at);
    }
}
