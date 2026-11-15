package com.agentos.storage.session;

import com.agentos.core.provider.CallMode;
import com.agentos.core.provider.FailureKind;
import com.agentos.core.provider.LlmCallRecord;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "llm_calls")
public class LlmCallEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "write_id", nullable = false, unique = true)
    private UUID writeId;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "turn_id", nullable = false)
    private UUID turnId;

    @Column(nullable = false)
    private Integer iteration;

    @Column(nullable = false)
    private String provider;

    @Column(name = "requested_model", nullable = false)
    private String requestedModel;

    @Column(name = "response_model")
    private String responseModel;

    @Column(name = "response_id")
    private String responseId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CallMode mode;

    @Column(nullable = false)
    private short attempt;

    @Column(name = "finish_reason")
    private String finishReason;

    @Column(name = "tool_call_count")
    private Integer toolCallCount;

    @Column(name = "image_count", nullable = false)
    private int imageCount;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Column(name = "total_tokens")
    private Integer totalTokens;

    @Column(name = "cache_read_tokens")
    private Long cacheReadTokens;

    @Column(name = "cache_write_tokens")
    private Long cacheWriteTokens;

    @Column(name = "requests_remaining")
    private Long requestsRemaining;

    @Column(name = "tokens_remaining")
    private Long tokensRemaining;

    @Column(name = "output_cap_honored")
    private Boolean outputCapHonored;

    @Column(nullable = false)
    private boolean success;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_kind")
    private FailureKind failureKind;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    protected LlmCallEntity() {}

    public LlmCallEntity(UUID writeId, LlmCallRecord r) {
        this.writeId = writeId;
        sessionId = r.sessionId();
        turnId = r.turnId();
        iteration = r.iteration();
        provider = r.provider();
        requestedModel = r.requestedModel();
        responseModel = r.responseModel();
        responseId = r.responseId();
        mode = r.mode();
        attempt = (short) r.attempt();
        finishReason = r.finishReason();
        toolCallCount = r.toolCallCount();
        imageCount = r.imageCount();
        promptTokens = r.promptTokens();
        completionTokens = r.completionTokens();
        totalTokens = r.totalTokens();
        cacheReadTokens = r.cacheReadTokens();
        cacheWriteTokens = r.cacheWriteTokens();
        requestsRemaining = r.requestsRemaining();
        tokensRemaining = r.tokensRemaining();
        outputCapHonored = r.outputCapHonored();
        success = r.success();
        failureKind = r.failureKind();
        httpStatus = r.httpStatus();
        errorMessage = r.errorSummary();
        createdAt = r.startedAt();
        durationMs = r.durationMs();
    }

    public Long getId() {
        return id;
    }

    public UUID getWriteId() {
        return writeId;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public UUID getTurnId() {
        return turnId;
    }

    public Integer getIteration() {
        return iteration;
    }

    public String getProvider() {
        return provider;
    }

    public String getRequestedModel() {
        return requestedModel;
    }

    public String getResponseModel() {
        return responseModel;
    }

    public String getResponseId() {
        return responseId;
    }

    public CallMode getMode() {
        return mode;
    }

    public int getAttempt() {
        return attempt;
    }

    public String getFinishReason() {
        return finishReason;
    }

    public Integer getToolCallCount() {
        return toolCallCount;
    }

    public int getImageCount() {
        return imageCount;
    }

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    public Integer getTotalTokens() {
        return totalTokens;
    }

    public Long getCacheReadTokens() {
        return cacheReadTokens;
    }

    public Long getCacheWriteTokens() {
        return cacheWriteTokens;
    }

    public Long getRequestsRemaining() {
        return requestsRemaining;
    }

    public Long getTokensRemaining() {
        return tokensRemaining;
    }

    public Boolean getOutputCapHonored() {
        return outputCapHonored;
    }

    public boolean isSuccess() {
        return success;
    }

    public FailureKind getFailureKind() {
        return failureKind;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public long getDurationMs() {
        return durationMs;
    }
}
