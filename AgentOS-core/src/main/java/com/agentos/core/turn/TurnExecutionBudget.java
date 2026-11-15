package com.agentos.core.turn;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** 用单调时钟计算整轮 deadline，并为收尾预留一秒 hard stop。 */
public final class TurnExecutionBudget {
    private final long deadlineNanos;
    private final long hardStopNanos;
    private final Instant deadline;

    private TurnExecutionBudget(long deadlineNanos, long hardStopNanos, Instant deadline) {
        this.deadlineNanos = deadlineNanos;
        this.hardStopNanos = hardStopNanos;
        this.deadline = deadline;
    }

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

    public Instant deadline() {
        return deadline;
    }

    public boolean expired() {
        return remainingNanos() <= 0;
    }

    public boolean hardStopped() {
        return System.nanoTime() >= hardStopNanos;
    }

    public long remainingNanos() {
        return Math.max(0, deadlineNanos - System.nanoTime());
    }

    public long remainingHardStopNanos() {
        return Math.max(0, hardStopNanos - System.nanoTime());
    }

    public Duration remaining() {
        return Duration.ofNanos(remainingNanos());
    }
}
