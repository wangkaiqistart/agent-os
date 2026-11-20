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
    // 等待人工授权时暂停计时：已结束的暂停累计到 pausedNanos，进行中的暂停从 pauseStartedNanos 起算。
    private final Object pauseLock = new Object();
    private long pausedNanos;
    private int pauseDepth;
    private long pauseStartedNanos;
    private final java.util.concurrent.atomic.AtomicLong cancellationStop =
            new java.util.concurrent.atomic.AtomicLong(Long.MAX_VALUE);
    private final java.util.concurrent.atomic.AtomicBoolean cancelled =
            new java.util.concurrent.atomic.AtomicBoolean();

    private final java.util.concurrent.atomic.AtomicBoolean auditUnconfirmed =
            new java.util.concurrent.atomic.AtomicBoolean();

    private final java.util.concurrent.atomic.AtomicInteger pendingSubmissions =
            new java.util.concurrent.atomic.AtomicInteger();

    /** 在真实提交边界登记；每次提交独立确认，后续成功不能抹去较早的不确定提交。 */
    public Submission beginSubmission() {
        pendingSubmissions.incrementAndGet();
        return new Submission();
    }

    public boolean submissionUnconfirmed() {
        return pendingSubmissions.get() > 0;
    }

    /** 只追加固定安全提示，不从异常原文猜测动作状态，也不改变持久化确认。 */
    public String submissionAwareText(String text) {
        return submissionUnconfirmed() ? text + "；提交可能已生效" : text;
    }

    public final class Submission {
        private final java.util.concurrent.atomic.AtomicBoolean confirmed =
                new java.util.concurrent.atomic.AtomicBoolean();

        private Submission() {}

        public void confirm() {
            if (confirmed.compareAndSet(false, true)) {
                pendingSubmissions.decrementAndGet();
            }
        }
    }

    private final java.util.Map<String, Integer> toolAttempts = new java.util.HashMap<>();
    private int totalToolAttempts;
    private final Object attemptLock = new Object();

    /** 官方 Manager 按模型调用计数；此处补记回调内部重试，避免实际尝试突破同一配置上限。 */
    public boolean reserveToolAttempt(String name, int perToolLimit, int totalLimit) {
        synchronized (attemptLock) {
            if (expired() || cancelled()) {
                throw new TurnDeadlineExceededException();
            }
            int used = toolAttempts.getOrDefault(name, 0);
            if ((perToolLimit >= 0 && used >= perToolLimit)
                    || (totalLimit >= 0 && totalToolAttempts >= totalLimit)) {
                return false;
            }
            toolAttempts.put(name, used + 1);
            totalToolAttempts++;
            return true;
        }
    }

    /** 任一必要审计未确认后，最终结果不能再宣称 CONFIRMED。 */
    public void auditUnconfirmed() {
        auditUnconfirmed.set(true);
    }

    public boolean auditsConfirmed() {
        return !auditUnconfirmed.get();
    }

    /** 取消原因；第一次取消的原因生效，用来区分"用户取消"与关闭、输出端失效等内部原因。 */
    public enum CancelReason {
        /** 用户主动取消，轮次以 CANCELLED 结束。 */
        USER,
        /** 进程关闭。 */
        SHUTDOWN,
        /** 输出端（事件消费方）失效。 */
        OUTPUT_FAILED,
        /** 超时、起步未确认、收尾等内部原因。 */
        INTERNAL
    }

    /**
     * 用户取消后留给循环退出和持久化收尾的时间。循环在执行线程被中断后通常很快退出，
     * 剩余时间用来把"已取消"写进数据库；这也是取消后 2 秒内结束的上限，固定值、不开放配置。
     */
    private static final Duration USER_CANCEL_WINDOW = Duration.ofSeconds(2);

    private static final Duration INTERNAL_CANCEL_WINDOW = Duration.ofSeconds(1);

    private final java.util.concurrent.atomic.AtomicReference<CancelReason> cancelReason =
            new java.util.concurrent.atomic.AtomicReference<>();

    /** 取消只阻止后续业务动作，不重置 deadline，也不抢占已有数据库事务。等价于内部原因取消。 */
    public void cancel() {
        cancel(CancelReason.INTERNAL);
    }

    /** 按原因取消：只收紧 hard stop，不会因为后来的取消而放宽；原因以第一次为准。 */
    public void cancel(CancelReason reason) {
        Objects.requireNonNull(reason, "reason");
        Duration window = reason == CancelReason.USER ? USER_CANCEL_WINDOW : INTERNAL_CANCEL_WINDOW;
        cancelReason.compareAndSet(null, reason);
        cancellationStop.accumulateAndGet(System.nanoTime() + window.toNanos(), Math::min);
        cancelled.set(true);
    }

    public boolean cancelled() {
        return cancelled.get();
    }

    /** 第一次取消的原因；未取消时为 null。 */
    public CancelReason cancelReason() {
        return cancelReason.get();
    }

    /** 是否由用户主动取消。 */
    public boolean userCancelled() {
        return cancelReason.get() == CancelReason.USER;
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

    /**
     * 暂停整轮计时，直到返回的句柄关闭；只用于等待人工授权，可嵌套。
     *
     * <p>暂停期间剩余预算保持不变；关闭后 deadline 与 hard stop 一起后移暂停时长。取消不受暂停影响。
     */
    public Pause pause() {
        synchronized (pauseLock) {
            if (pauseDepth++ == 0) {
                pauseStartedNanos = System.nanoTime();
            }
        }
        return new Pause();
    }

    public final class Pause implements AutoCloseable {
        private final java.util.concurrent.atomic.AtomicBoolean closed =
                new java.util.concurrent.atomic.AtomicBoolean();

        private Pause() {}

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            synchronized (pauseLock) {
                if (--pauseDepth == 0) {
                    pausedNanos += System.nanoTime() - pauseStartedNanos;
                }
            }
        }
    }

    /** 累计暂停过的时长（含进行中的暂停）；为 0 表示截止时间从未后移。 */
    public long pausedNanos() {
        return shiftNanos(System.nanoTime());
    }

    /** 到 now 为止累计暂停的时长，含进行中的暂停。 */
    private long shiftNanos(long now) {
        synchronized (pauseLock) {
            return pausedNanos + (pauseDepth > 0 ? now - pauseStartedNanos : 0);
        }
    }

    /** 适合存储或跨组件传递的绝对时间；进程内是否耗尽以单调计时检查为准。 */
    public Instant deadline() {
        return deadline.plusNanos(shiftNanos(System.nanoTime()));
    }

    public long deadlineNanos() {
        return deadlineNanos + shiftNanos(System.nanoTime());
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
        long now = System.nanoTime();
        return Math.max(0, deadlineNanos + shiftNanos(now) - now);
    }

    /** TurnRunner 使用的外层等待余量，包含额外的一秒窗口。 */
    public long remainingHardStopNanos() {
        long now = System.nanoTime();
        return Math.max(0, Math.min(hardStopNanos + shiftNanos(now), cancellationStop.get()) - now);
    }

    public Duration remaining() {
        return Duration.ofNanos(remainingNanos());
    }
}
