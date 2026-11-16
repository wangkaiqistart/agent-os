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

/**
 * llm_calls 表映射：一行对应一次模型请求尝试，同一轮次重试时可能产生多行。
 *
 * <p>保存关联、模型标识、用量、失败分类和耗时，不保存完整 Prompt/答案。
 * 上游 LlmCallRecord 已完成元数据归一化，此处只映射到列。
 */
@Entity
@Table(name = "llm_calls")
public class LlmCallEntity {
    // id 用于数据库行定位；writeId 是每次 record 入口生成、在 SQL 重试间复用的去重键，两者用途不同。
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

    // 枚举存名称而非 ordinal，避免将来调整 Java 枚举顺序后把旧数据读成另一种模式。
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

    // 包装类型保留“服务端未报告”的 NULL；未知用量/缓存/限流余额不能假装成 0。
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

    // 三态：遵守/未遵守/无法判断输出上限，不能用 primitive boolean 丢失未知状态。
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

    /** JPA 读取用；业务写入使用 LlmCallRecord 构造器。 */
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
        // created_at 记录请求开始时刻，并非数据库插入时刻。
        createdAt = r.startedAt();
        durationMs = r.durationMs();
    }

    /** 完整载荷比较用于提交丢响应后的查证，不能仅比较业务关联或吞唯一键冲突。 */
    public LlmCallRecord toRecord() {
        return new LlmCallRecord(
                sessionId,
                turnId,
                iteration,
                provider,
                requestedModel,
                responseModel,
                responseId,
                mode,
                attempt,
                finishReason,
                toolCallCount,
                imageCount,
                promptTokens,
                completionTokens,
                totalTokens,
                cacheReadTokens,
                cacheWriteTokens,
                requestsRemaining,
                tokensRemaining,
                outputCapHonored,
                success,
                failureKind,
                httpStatus,
                errorMessage,
                createdAt,
                durationMs);
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
