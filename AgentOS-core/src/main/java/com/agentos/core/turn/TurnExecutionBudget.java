package com.agentos.core.turn;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 一轮执行共享的时间预算，同时保留单调计时和可对外传递的绝对截止时间。
 *
 * <p>System.nanoTime 用于进程内计算经过多久，不受系统时间校准影响；Instant 用于写入数据库
 * 及传给 Provider。正常 deadline 后额外留一秒作为外层 hard stop，但这不是强制终止所有 I/O 的保证。
 */
public final class TurnExecutionBudget {
    private final long deadlineNanos;
    private final long hardStopNanos;
    private final Instant deadline;

    private TurnExecutionBudget(long deadlineNanos, long hardStopNanos, Instant deadline) {
        this.deadlineNanos = deadlineNanos;
        this.hardStopNanos = hardStopNanos;
        this.deadline = deadline;
    }

    /** 从调用时刻开始计时；后续步骤共享这个对象，不能在每次模型调用时重置整轮预算。 */
    public static TurnExecutionBudget start(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("预算必须为正数");
        }
        long now = System.nanoTime();
        long nanos = duration.toNanos();
        return new TurnExecutionBudget(
                now + nanos,
                now + nanos + TimeUnit.SECONDS.toNanos(1),
                Instant.now().plus(duration));
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
        return System.nanoTime() >= hardStopNanos;
    }

    /** 余量最小返回零，避免负数被下游误当作无限超时等特殊值。 */
    public long remainingNanos() {
        return Math.max(0, deadlineNanos - System.nanoTime());
    }

    /** TurnRunner 使用的外层等待余量，包含额外的一秒窗口。 */
    public long remainingHardStopNanos() {
        return Math.max(0, hardStopNanos - System.nanoTime());
    }

    public Duration remaining() {
        return Duration.ofNanos(remainingNanos());
    }
}
