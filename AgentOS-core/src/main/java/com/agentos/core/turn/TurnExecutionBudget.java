package com.agentos.core.turn;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 一轮执行共享的时间预算，同时保留单调计时和可对外传递的绝对截止时间。
 *
 * <p>System.nanoTime 用于进程内计算经过多久，不受系统时间校准影响；Instant 用于写入数据库
 * 及传给 Provider。正常 deadline 后额外保留 cleanup 窗口作为外层 hard stop，但这不是强制终止所有 I/O 的保证。
 */
public final class TurnExecutionBudget {
    private final long deadlineNanos;
    private final long hardStopNanos;
    private final Instant deadline;
    private final java.util.concurrent.atomic.AtomicLong cancellationStop =
            new java.util.concurrent.atomic.AtomicLong(Long.MAX_VALUE);
    private final java.util.concurrent.atomic.AtomicBoolean cancelled =
            new java.util.concurrent.atomic.AtomicBoolean();

    private final java.util.concurrent.atomic.AtomicBoolean auditUnconfirmed =
            new java.util.concurrent.atomic.AtomicBoolean();

    /** 任一必要审计未确认后，最终结果不能再宣称 CONFIRMED。 */
    public void auditUnconfirmed() {
        auditUnconfirmed.set(true);
    }

    public boolean auditsConfirmed() {
        return !auditUnconfirmed.get();
    }

    /** 取消只阻止后续业务动作，不重置 deadline，也不抢占已有数据库事务。 */
    public void cancel() {
        cancellationStop.accumulateAndGet(
                System.nanoTime() + Duration.ofSeconds(1).toNanos(), Math::min);
        cancelled.set(true);
    }

    public boolean cancelled() {
        return cancelled.get();
    }

    private TurnExecutionBudget(long deadlineNanos, long hardStopNanos, Instant deadline) {
        this.deadlineNanos = deadlineNanos;
        this.hardStopNanos = hardStopNanos;
        this.deadline = deadline;
    }

    /** 从调用时刻开始计时；后续步骤共享这个对象，不能在每次模型调用时重置整轮预算。 */
    public static TurnExecutionBudget start(Duration duration) {
        return start(duration, Duration.ofSeconds(1));
    }

    /** 清理窗口只用于有限的持久化/通知等待，不会延长业务 deadline。 */
    public static TurnExecutionBudget start(Duration duration, Duration cleanup) {
        Objects.requireNonNull(duration, "duration");
        Objects.requireNonNull(cleanup, "cleanup");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("预算必须为正数");
        }
        if (cleanup.isZero() || cleanup.isNegative()) {
            throw new IllegalArgumentException("清理窗口必须为正数");
        }
        long now = System.nanoTime();
        long nanos = duration.toNanos();
        return new TurnExecutionBudget(
                now + nanos, now + nanos + cleanup.toNanos(), Instant.now().plus(duration));
    }

    /** 适合存储或跨组件传递的绝对时间；进程内是否耗尽以单调计时检查为准。 */
    public Instant deadline() {
        return deadline;
    }

    /** 业务预算是否用尽：到期后 checkpoint 拒绝继续调用模型或追加答案。 */
    public boolean expired() {
        return remainingNanos() <= 0;
    }

    /** 是否连额外的一秒等待窗口也用尽。 */
    public boolean hardStopped() {
        return remainingHardStopNanos() <= 0;
    }

    /** 余量最小返回零，避免负数被下游误当作无限超时等特殊值。 */
    public long remainingNanos() {
        return Math.max(0, deadlineNanos - System.nanoTime());
    }

    /** TurnRunner 使用的外层等待余量，包含额外的一秒窗口。 */
    public long remainingHardStopNanos() {
        return Math.max(0, Math.min(hardStopNanos, cancellationStop.get()) - System.nanoTime());
    }

    public Duration remaining() {
        return Duration.ofNanos(remainingNanos());
    }
}
