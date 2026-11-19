package com.agentos.core.tool;

import com.agentos.core.turn.TurnContext;
import com.agentos.core.turn.TurnDeadlineExceededException;
import com.agentos.core.turn.TurnExecutionBudget;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/** 显式工具调用预算及资源所有权；不是模型参数或远程元数据。 */
public final class ToolExecutionScope implements AutoCloseable {
    private final TurnContext turn;
    private final String callId;
    private final TurnExecutionBudget budget;
    private final Runnable checkpoint;
    private final java.util.concurrent.locks.ReentrantLock checkpointLock =
            new java.util.concurrent.locks.ReentrantLock();
    private final LongSupplier clock;
    // 工具自身的截止时刻（未计暂停）；实际截止还受整轮预算约束，见 deadlineNanos()。
    private final long ownDeadlineNanos;
    private final Object pauseLock = new Object();
    private long pausedNanos;
    private int pauseDepth;
    private long pauseStartedNanos;
    // 工具内部 I/O 检查点复用最近一次续租的最短间隔；0 表示每次都续租。
    private final long renewIntervalNanos;
    // 以下两项只在 checkpointLock 内读写。
    private boolean renewedOnce;
    private long lastRenewedNanos;

    private record OwnedResource(Object key, CancellationResource cancellation, Thread worker) {}

    private final Map<Object, OwnedResource> resources = new LinkedHashMap<>();
    private final java.util.Set<Object> activeWorkers = new java.util.HashSet<>();
    private final AtomicReference<ExecutionState> state =
            new AtomicReference<>(ExecutionState.NOT_STARTED);
    private final AtomicBoolean closed = new AtomicBoolean();
    // 初始及清理在途均未确认；仅首个关闭者在所有资源完成后发布终态。
    private volatile boolean cleanupConfirmed;
    private volatile boolean cleanupFinished;
    private volatile OwnedResource selfClosingWorker;
    private TurnExecutionBudget.Submission submission;
    private volatile long cleanupDeadline = Long.MAX_VALUE;

    public enum ExecutionState {
        NOT_STARTED,
        POSSIBLY_STARTED,
        COMPLETED
    }

    @FunctionalInterface
    public interface CancellationResource {
        boolean cancel(long deadlineNanos) throws Exception;
    }

    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }

    /** pauseClock 的恢复句柄；关闭即恢复计时，重复关闭无效果。 */
    public interface Paused extends AutoCloseable {
        @Override
        void close();
    }

    public ToolExecutionScope(
            TurnContext turn,
            String callId,
            TurnExecutionBudget budget,
            Duration timeout,
            Runnable checkpoint) {
        this(turn, callId, budget, timeout, checkpoint, Duration.ZERO, System::nanoTime);
    }

    /**
     * 治理层使用：工具内部热循环（逐行搜索、输出排空、等待轮询）的检查点在 renewInterval 内复用上次续租，
     * 预算与取消仍每次检查；{@link #checkpointAndRenew()} 不受此限制。
     */
    public ToolExecutionScope(
            TurnContext turn,
            String callId,
            TurnExecutionBudget budget,
            Duration timeout,
            Runnable checkpoint,
            Duration renewInterval) {
        this(turn, callId, budget, timeout, checkpoint, renewInterval, System::nanoTime);
    }

    public ToolExecutionScope(
            TurnContext turn,
            String callId,
            TurnExecutionBudget budget,
            Duration timeout,
            Runnable checkpoint,
            LongSupplier clock) {
        this(turn, callId, budget, timeout, checkpoint, Duration.ZERO, clock);
    }

    public ToolExecutionScope(
            TurnContext turn,
            String callId,
            TurnExecutionBudget budget,
            Duration timeout,
            Runnable checkpoint,
            Duration renewInterval,
            LongSupplier clock) {
        this.turn = turn;
        this.callId = Objects.requireNonNull(callId);
        this.budget = Objects.requireNonNull(budget);
        this.checkpoint = Objects.requireNonNull(checkpoint);
        this.clock = Objects.requireNonNull(clock);
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("工具预算必须为正数");
        }
        if (renewInterval == null || renewInterval.isNegative()) {
            throw new IllegalArgumentException("续租间隔不能为负数");
        }
        renewIntervalNanos = renewInterval.toNanos();
        ownDeadlineNanos = clock.getAsLong() + timeout.toNanos();
    }

    public UUID turnId() {
        return turn == null ? null : turn.turnId();
    }

    public String callId() {
        return callId;
    }

    public TurnExecutionBudget budget() {
        return budget;
    }

    public long deadlineNanos() {
        long now = clock.getAsLong();
        long shift;
        synchronized (pauseLock) {
            shift = pausedNanos + (pauseDepth > 0 ? now - pauseStartedNanos : 0);
        }
        return Math.min(ownDeadlineNanos + shift, budget.deadlineNanos());
    }

    /**
     * 等待人工授权期间同时暂停工具自身计时和整轮计时；关闭返回的句柄后恢复，可嵌套。
     *
     * <p>取消、租约检查不受影响，等待方仍应持续调用 {@link #checkpoint()}。
     */
    public Paused pauseClock() {
        var turnPause = budget.pause();
        synchronized (pauseLock) {
            if (pauseDepth++ == 0) {
                pauseStartedNanos = clock.getAsLong();
            }
        }
        var closed = new AtomicBoolean();
        return () -> {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            synchronized (pauseLock) {
                if (--pauseDepth == 0) {
                    pausedNanos += clock.getAsLong() - pauseStartedNanos;
                }
            }
            turnPause.close();
        };
    }

    public long remainingNanos() {
        return Math.max(0, deadlineNanos() - clock.getAsLong());
    }

    /** 工具内部 I/O 检查点：每次检查预算和取消，续租按 renewInterval 限频。 */
    public void checkpoint() {
        checkpoint(false);
    }

    /** 治理边界（每次尝试前、取得结果后）的检查点：总是续租。 */
    public void checkpointAndRenew() {
        checkpoint(true);
    }

    private void checkpoint(boolean forceRenew) {
        checkBudget();
        try {
            // stdout/stderr/等待线程共享同一租约；串行检查，避免本轮续租事务互相抢同一行锁。
            // 等待锁也扣原预算，不能让排队检查在截止后再发起数据库动作。
            for (; ; ) {
                checkBudget();
                if (checkpointLock.tryLock(
                        Math.max(
                                1,
                                Math.min(
                                        TimeUnit.MILLISECONDS.toNanos(50),
                                        Math.min(remainingNanos(), budget.remainingNanos()))),
                        TimeUnit.NANOSECONDS)) {
                    break;
                }
                // 已在等待的线程也观察取消，不等到原 deadline 才离开队列。
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            checkBudget();
            throw new com.agentos.core.turn.LeaseLostException();
        }
        try {
            checkBudget();
            if (closed.get()) {
                throw new IllegalStateException("工具调用已关闭");
            }
            // 线程已中断时不复用，交给续租检查点按租约丢失处理。
            boolean reuse =
                    !forceRenew
                            && renewIntervalNanos > 0
                            && renewedOnce
                            && clock.getAsLong() - lastRenewedNanos < renewIntervalNanos
                            && !Thread.currentThread().isInterrupted();
            if (!reuse) {
                checkpoint.run();
                lastRenewedNanos = clock.getAsLong();
                renewedOnce = true;
            }
            checkBudget();
            if (closed.get()) {
                throw new IllegalStateException("工具调用已关闭");
            }
        } finally {
            checkpointLock.unlock();
        }
    }

    private void checkBudget() {
        if (budget.cancelled() || budget.expired()) {
            throw new TurnDeadlineExceededException();
        }
        if (remainingNanos() <= 0) {
            throw new ToolTimeoutException();
        }
    }

    public void possiblyStarted() {
        checkpoint();
        state.compareAndSet(ExecutionState.NOT_STARTED, ExecutionState.POSSIBLY_STARTED);
    }

    /** 仅提交工具在最终校验完成、即将发送时调用，不等同于进入通用委托。 */
    public void submissionPossiblySent() {
        checkpoint();
        synchronized (resources) {
            if (closed.get()) {
                throw new IllegalStateException("工具调用已关闭");
            }
            if (submission == null) {
                submission = budget.beginSubmission();
            }
        }
    }

    /** 成功响应、治理、资源清理及完成审计均已确认后，才确认本次提交。 */
    public void confirmSubmission() {
        synchronized (resources) {
            if (submission != null) {
                submission.confirm();
            }
        }
    }

    public void completed() {
        state.set(ExecutionState.COMPLETED);
    }

    public ExecutionState executionState() {
        return state.get();
    }

    public Registration register(CancellationResource resource) {
        return register(resource, null);
    }

    /** 工作线程先登记再启动；由治理方观察委托退出后注销，不能用 Future 取消代替退出确认。 */
    public Registration registerWorker(Thread worker, CancellationResource resource) {
        return register(resource, Objects.requireNonNull(worker));
    }

    private Registration register(CancellationResource resource, Thread worker) {
        Objects.requireNonNull(resource);
        synchronized (resources) {
            if (closed.get()) {
                throw new IllegalStateException("工具调用已关闭");
            }
            Object key = new Object();
            resources.put(key, new OwnedResource(key, resource, worker));
            if (worker != null) {
                activeWorkers.add(key);
            }
            return () -> {
                synchronized (resources) {
                    resources.remove(key);
                    activeWorkers.remove(key);
                }
            };
        }
    }

    public boolean cleanupConfirmed() {
        synchronized (resources) {
            return cleanupConfirmed && activeWorkers.isEmpty();
        }
    }

    public String safeReason() {
        return cleanupConfirmed() ? null : "CLEANUP_UNCONFIRMED";
    }

    /** 审计与资源关闭共享这一次窗口，不向每个资源另发一秒。 */
    public long remainingCleanupNanos() {
        return Math.min(
                budget.remainingHardStopNanos(), Math.max(0, cleanupDeadline - clock.getAsLong()));
    }

    /** 取消与开始清理按同一所有权锁协调，不能中断已在清理资源的工作线程。 */
    public void cancelWorker(Thread worker, Runnable cancellation) {
        synchronized (resources) {
            if (selfClosingWorker == null
                    || selfClosingWorker.worker() != worker
                    || cleanupFinished) {
                cancellation.run();
            }
        }
    }

    /** 仅治理方使用：工作线程已开始清理时，仍在原窗口内等待它退出，不提前发布终结结果。 */
    public void awaitWorkerCleanup() {
        var worker = selfClosingWorker;
        if (worker == null || worker.worker() == Thread.currentThread() || cleanupFinished) {
            return;
        }
        boolean restoreInterrupt = Thread.interrupted();
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var future = executor.submit(() -> cancel(worker));
        try {
            future.get(Math.max(1, remainingCleanupNanos()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            restoreInterrupt = true;
        } catch (java.util.concurrent.ExecutionException
                | java.util.concurrent.TimeoutException failure) {
            // 原窗口内不能确认时保持未确认，不能重开窗口或把取消Future当作停止。
        } finally {
            future.cancel(true);
            executor.shutdownNow();
            if (restoreInterrupt) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private boolean cancel(OwnedResource resource) throws Exception {
        boolean stopped = resource.cancellation().cancel(cleanupDeadline);
        if (stopped && resource.worker() != null) {
            synchronized (resources) {
                activeWorkers.remove(resource.key());
            }
        }
        return stopped;
    }

    public void close() {
        java.util.List<OwnedResource> owned;
        synchronized (resources) {
            if (!closed.compareAndSet(false, true)) {
                // 资源清理可能等待调用本身结束；并发/递归关闭不互相等待，也不重开窗口。
                return;
            }
            cleanupDeadline =
                    clock.getAsLong()
                            + Math.min(
                                    Duration.ofSeconds(1).toNanos(),
                                    budget.remainingHardStopNanos());
            owned = new ArrayList<>();
            for (OwnedResource resource : resources.values()) {
                // 自身关闭时只清理其他资源；当前委托仍在途，退出前始终不确认整体清理。
                if (resource.worker() != Thread.currentThread()) {
                    owned.add(resource);
                } else {
                    selfClosingWorker = resource;
                }
            }
            resources.clear();
        }
        boolean restoreInterrupt = Thread.interrupted();
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        boolean confirmed = false;
        try {
            boolean allConfirmed = true;
            var futures =
                    owned.stream()
                            .map(resource -> executor.submit(() -> cancel(resource)))
                            .toList();
            for (var future : futures) {
                try {
                    if (!future.get(Math.max(1, remainingCleanupNanos()), TimeUnit.NANOSECONDS)) {
                        allConfirmed = false;
                    }
                } catch (InterruptedException interrupted) {
                    restoreInterrupt = true;
                    allConfirmed = false;
                    future.cancel(true);
                } catch (java.util.concurrent.ExecutionException
                        | java.util.concurrent.TimeoutException failure) {
                    allConfirmed = false;
                    future.cancel(true);
                }
            }
            confirmed = allConfirmed;
        } finally {
            executor.shutdownNow();
            cleanupConfirmed = confirmed;
            cleanupFinished = true;
            if (restoreInterrupt) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public static final class ToolTimeoutException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ToolTimeoutException() {
            super("工具自身超时");
        }
    }
}
