package com.agentos.core.turn;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 把轮次任务交给独立虚拟线程，并限制调用方等待到 hard stop。
 *
 * <p>这是一段显式 Java 并发逻辑，不使用 Spring 的 @Async。任务线程需要的预算和轮次信息
 * 必须由调用方显式传入；调用线程的 Spring 事务和 MDC 不会因此自动传播。
 */
public final class TurnRunner {
    /** 同步等待任务结果；运行时业务异常保留原类型，超时或等待被中断统一转成轮次超时。 */
    public <T> T run(Callable<T> task, TurnExecutionBudget budget) {
        Objects.requireNonNull(task);
        Objects.requireNonNull(budget);
        // 每次 run 使用自己的执行器，不在 finally 中关闭其他轮次正在共享的线程池。
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<T> future = executor.submit(task);
            try {
                // 至少等待 1ns，耗尽时也不传入可能被其他 API 解释为“无限”的零超时。
                return future.get(
                        Math.max(1, budget.remainingHardStopNanos()), TimeUnit.NANOSECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                // cancel(true) 只是请求中断；Provider 的 HTTP 超时仍是停止外部 I/O 的重要约束。
                budget.cancel();
                future.cancel(true);
                throw new TurnDeadlineExceededException();
            } catch (InterruptedException e) {
                // InterruptedException 会清掉中断标志，恢复后让上层也能感知关闭或取消。
                Thread.currentThread().interrupt();
                budget.cancel();
                future.cancel(true);
                throw new TurnDeadlineExceededException();
            } catch (ExecutionException e) {
                // Future 会包一层异常；解开后 AgentService 才能识别 TurnDeadlineExceededException 等类型。
                if (e.getCause() instanceof RuntimeException r) {
                    throw r;
                }
                throw new IllegalStateException(e.getCause());
            }
        } finally {
            // 无论任务如何退出都回收执行器；等待有上限，避免不响应中断的任务拖住调用方。
            executor.shutdownNow();
        }
    }
}
