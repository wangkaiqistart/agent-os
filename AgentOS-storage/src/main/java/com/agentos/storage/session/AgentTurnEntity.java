package com.agentos.storage.session;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * agent_turns 表映射：每条用户输入对应一轮，记录执行状态、截止时间和终态摘要。
 *
 * <p>新建状态为 RUNNING；与 sessions 的 active/archived 状态不是同一个维度。
 * 实体方法只改变内存中的值，持久化和事务由 JpaTurnStore 负责。
 */
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

    // 正常循环次数与终态一起提交；致命异常收口目前按契约记 0，实际调用另查审计。
    @Column(nullable = false)
    private int iterations;

    @Column(name = "final_text")
    private String finalText;

    @Column(name = "error_message")
    private String errorMessage;

    // started/finished 用于审计耗时，deadline 用于业务超时；会话 leaseUntil 另存于 sessions。
    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "deadline_at", nullable = false)
    private Instant deadlineAt;

    /** 供 JPA 恢复持久化对象。 */
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

    /** 记录终态和对外文本，不会自动追加 session_messages，也不负责释放会话租约。 */
    public void finish(String status, String text, String error, Instant at) {
        this.status = status;
        finalText = text;
        errorMessage = error;
        finishedAt = at;
    }

    public void iteration(int value) {
        iterations = value;
    }

    /** 为租约失效的残留轮次收尾；与用户正常失败、业务超时分开标识。 */
    public void interrupt(String reason, Instant at) {
        finish("INTERRUPTED", "本轮已中断", reason, at);
    }
}
