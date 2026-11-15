package com.agentos.core.turn;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** 对整轮任务提供 deadline+cleanup 的有界等待。 */
public final class TurnRunner {
    public <T> T run(Callable<T> task, TurnExecutionBudget budget) {
        Objects.requireNonNull(task);
        Objects.requireNonNull(budget);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<T> future = executor.submit(task);
            try {
                return future.get(
                        Math.max(1, budget.remainingHardStopNanos()), TimeUnit.NANOSECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                future.cancel(true);
                throw new TurnDeadlineExceededException();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                future.cancel(true);
                throw new TurnDeadlineExceededException();
            } catch (ExecutionException e) {
                if (e.getCause() instanceof RuntimeException r) {
                    throw r;
                }
                throw new IllegalStateException(e.getCause());
            }
        } finally {
            executor.shutdownNow();
            try {
                executor.awaitTermination(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
