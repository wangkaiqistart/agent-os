package com.agentos.core.turn;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 输出端的生命周期边界。过程回调异常/仍在途时禁止再次进入；终结回调只尝试一次。
 * 不持锁调用用户代码，避免阻塞的输出端把取消和 hard stop 一起锁住。
 */
final class ManagedTurnSink implements TurnSink {
    private static final Logger log = LoggerFactory.getLogger(ManagedTurnSink.class);
    private final TurnSink delegate;
    private final TurnExecutionBudget budget;
    private final AtomicBoolean valid = new AtomicBoolean(true);
    private final AtomicBoolean busy = new AtomicBoolean();
    private final AtomicBoolean terminal = new AtomicBoolean();

    ManagedTurnSink(TurnSink delegate, TurnExecutionBudget budget) {
        this.delegate = delegate;
        this.budget = budget;
    }

    public boolean streaming() {
        return delegate.streaming();
    }

    private void emit(Runnable callback) {
        if (!valid.get() || terminal.get()) {
            throw new LeaseLostException();
        }
        if (budget.expired()) {
            throw new TurnDeadlineExceededException();
        }
        if (!busy.compareAndSet(false, true)) {
            throw new LeaseLostException();
        }
        try {
            callback.run();
        } catch (Throwable error) {
            valid.set(false);
            budget.cancel();
            throw new LeaseLostException();
        } finally {
            busy.set(false);
        }
    }

    public void onDelta(String text) {
        emit(() -> delegate.onDelta(text));
    }

    public void onToolCall(String id, String name, String arguments) {
        emit(() -> delegate.onToolCall(id, name, arguments));
    }

    public void onToolResult(String id, String name, String result) {
        emit(() -> delegate.onToolResult(id, name, result));
    }

    void finish(TurnResult result) {
        if (!terminal.compareAndSet(false, true)) {
            return;
        }
        if (!valid.get() || busy.get() || budget.hardStopped()) {
            log.warn("轮次终结通知未送达：输出端不可用或通知预算耗尽");
            return;
        }
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var future =
                executor.submit(() -> {
                    if (result.status() == TurnStatus.SUCCEEDED) {
                        delegate.onComplete(result);
                    } else {
                        delegate.onError(result);
                    }
                });
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
}
