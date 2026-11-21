package com.agentos.core.turn;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 本轮的事件发布器：分配序号、串行投递给输出端，并守住"终态只有一条且在最后"。
 *
 * <p>流式文字在模型流的回调线程、工具事件在轮次线程或工具执行线程上发布，逻辑上先后发生但线程不同，
 * 所以用锁排队而不是"并发即失败"。用 {@link ReentrantLock} 而不是 synchronized，避免虚拟线程在持锁调用
 * 输出端时被钉住。等锁与投递只受 hard stop 约束；过程事件不检查整轮截止时间，超时后已开始的工具、步骤的结束
 * 事件仍要送达，只有新的模型文字在到期后被拒绝。输出端抛异常即失效，之后的发布一律拒绝。
 */
final class ManagedTurnSink implements TurnSink, TurnEventPublisher {
    private static final Logger log = LoggerFactory.getLogger(ManagedTurnSink.class);
    private final TurnSink delegate;
    private final TurnExecutionBudget budget;
    private final UUID turnId;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile UUID sessionId;
    private volatile boolean valid = true;
    private volatile boolean terminal;
    private volatile boolean sideEffectUncertain;
    private long nextSeq = 1;

    ManagedTurnSink(TurnSink delegate, TurnExecutionBudget budget, UUID turnId) {
        this.delegate = TurnSink.require(delegate);
        this.budget = java.util.Objects.requireNonNull(budget, "budget");
        this.turnId = java.util.Objects.requireNonNull(turnId, "turnId");
    }

    /** startTurn 成功后绑定会话标识；绑定前发布的事件会话标识为空（只用于起步未确认）。 */
    void bindSession(UUID sessionId) {
        this.sessionId = sessionId;
    }

    @Override
    public boolean streaming() {
        return delegate.streaming();
    }

    @Override
    public void publish(TurnEventPayload payload) {
        java.util.Objects.requireNonNull(payload, "payload");
        if (payload.terminal()) {
            throw new IllegalArgumentException("终态事件只能由 finish 发布");
        }
        if (budget.expired()
                && (payload instanceof TurnEventPayload.MessageDelta
                        || payload instanceof TurnEventPayload.MessageStarted)) {
            // 到期后不再接收新的模型文字：不响应截止时间的模型流靠这里停下。过程事件不受影响，
            // 已开始的工具、步骤的结束事件仍要送达。
            throw new TurnDeadlineExceededException();
        }
        if (!acquire()) {
            invalidate();
            throw new LeaseLostException();
        }
        try {
            if (!valid || terminal) {
                throw new LeaseLostException();
            }
            TurnEvent event = next(payload);
            if (payload instanceof TurnEventPayload.ToolFinished finished
                    && finished.possiblyExecuted()
                    && finished.status() != TurnEventPayload.ToolStatus.SUCCESS) {
                sideEffectUncertain = true;
            }
            try {
                delegate.onEvent(event);
            } catch (Throwable error) {
                invalidate();
                throw new LeaseLostException();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 发出唯一的终态事件，只执行一次。输出端已失效、仍有过程回调在途或 hard stop 已耗尽时只记日志，
     * 不改变已确认的业务结果；投递在受管任务中有界等待，失败也只记日志。
     */
    void finish(TurnResult result) {
        TurnEvent event;
        if (!lock.tryLock()) {
            terminal = true;
            log.warn("轮次终结通知未送达：输出端不可用或通知预算耗尽");
            return;
        }
        try {
            if (terminal) {
                return;
            }
            terminal = true;
            if (!valid || budget.hardStopped()) {
                log.warn("轮次终结通知未送达：输出端不可用或通知预算耗尽");
                return;
            }
            try {
                event = next(terminalPayload(result));
            } catch (RuntimeException invalid) {
                // 终结通知不能改变已确认的业务结果，构造失败也只记日志。
                log.warn("轮次终结通知未送达：{}", invalid.getClass().getSimpleName());
                return;
            }
        } finally {
            lock.unlock();
        }
        // terminal 已置位，之后的 publish 都会被拒绝；投递放到受管任务里，避免阻塞的输出端拖住 hard stop。
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var future = executor.submit(() -> delegate.onEvent(event));
        try {
            future.get(Math.max(1, budget.remainingHardStopNanos()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("轮次终结通知未送达：等待中断");
        } catch (ExecutionException | TimeoutException e) {
            log.warn("轮次终结通知未送达：回调失败或超时");
        } finally {
            future.cancel(true);
            executor.shutdownNow();
        }
    }

    /** 本轮是否有非幂等工具在失败或中断时可能已经生效；终态与取消文本据此提示用户。 */
    boolean sideEffectUncertain() {
        return sideEffectUncertain;
    }

    /** 只在持锁时调用。 */
    private TurnEvent next(TurnEventPayload payload) {
        return new TurnEvent(
                TurnEvent.SCHEMA_VERSION, sessionId, turnId, nextSeq++, Instant.now(), payload);
    }

    /**
     * 等锁上限取 hard stop 余量。执行线程可能已因用户取消被中断，等锁不能因此失败：
     * 先清掉中断标志再等，结束后恢复，让后续检查点照常感知取消。
     */
    private boolean acquire() {
        boolean interrupted = Thread.interrupted();
        try {
            return lock.tryLock(Math.max(1, budget.remainingHardStopNanos()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            interrupted = true;
            return false;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void invalidate() {
        valid = false;
        budget.cancel(TurnExecutionBudget.CancelReason.OUTPUT_FAILED);
    }

    private TurnEventPayload terminalPayload(TurnResult result) {
        boolean possiblyExecuted = budget.submissionUnconfirmed() || sideEffectUncertain;
        return switch (result.status()) {
            case SUCCEEDED ->
                    new TurnEventPayload.RunCompleted(
                            result.text(), result.iterations(), result.persistenceStatus());
            case CANCELLED ->
                    new TurnEventPayload.RunCancelled(
                            result.text(),
                            result.iterations(),
                            result.persistenceStatus(),
                            possiblyExecuted);
            default ->
                    new TurnEventPayload.RunFailed(
                            result.failureCode() == null
                                    ? defaultCode(result.status())
                                    : result.failureCode(),
                            result.status(),
                            result.text(),
                            result.iterations(),
                            result.persistenceStatus(),
                            possiblyExecuted);
        };
    }

    /** 调用方没给错误码时按状态补一个最接近的值。 */
    private static TurnFailureCode defaultCode(TurnStatus status) {
        return switch (status) {
            case TIMED_OUT -> TurnFailureCode.TIMED_OUT;
            case MAX_ITERATIONS -> TurnFailureCode.MAX_ITERATIONS;
            case LIMIT_EXCEEDED -> TurnFailureCode.TOOL_LIMIT_EXCEEDED;
            case INTERRUPTED -> TurnFailureCode.LEASE_LOST;
            default -> TurnFailureCode.INTERNAL;
        };
    }
}
