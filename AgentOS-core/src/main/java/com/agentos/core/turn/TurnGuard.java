package com.agentos.core.turn;

import com.agentos.core.session.TurnSnapshot;
import com.agentos.core.session.TurnStore;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 高成本操作和消息追加前的统一检查点：先查整轮预算，再尝试续租。
 *
 * <p>预算有效只能说明“还来得及”，租约有效才说明“这一轮仍有权处理该会话”；两者缺一不可。
 * 续租通过存储端口完成，不能靠进程内锁代替数据库中的跨实例协调。
 */
public final class TurnGuard {
    private final TurnStore turnStore;

    public TurnGuard(TurnStore turnStore) {
        this.turnStore = Objects.requireNonNull(turnStore);
    }

    /** 检查失败立即抛出运行时异常，由 AgentService 持久化相应失败或超时终态。 */
    public void checkpoint(TurnSnapshot turn, TurnExecutionBudget budget) {
        if (budget.expired() || budget.hardStopped()) {
            throw new TurnDeadlineExceededException();
        }
        if (budget.cancelled() || Thread.currentThread().isInterrupted()) {
            throw new LeaseLostException();
        }
        boolean renewed = renewWithinBudget(turn, budget);
        // 续租的连接/锁/往返也会消耗预算，返回后先判 deadline，再解释租约结果。
        if (budget.expired() || budget.hardStopped()) {
            throw new TurnDeadlineExceededException();
        }
        if (!renewed || budget.cancelled()) {
            throw new LeaseLostException();
        }
    }

    /** 数据库续租也必须受本轮预算约束，避免检查点把 hard stop 变成无限等待。 */
    private boolean renewWithinBudget(TurnSnapshot turn, TurnExecutionBudget budget) {
        long remaining = budget.remainingNanos();
        if (remaining <= 0) {
            throw new TurnDeadlineExceededException();
        }
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var future = executor.submit(() -> turnStore.renewLease(turn, budget));
        try {
            return future.get(
                    Math.max(1, Math.min(remaining, budget.remainingHardStopNanos())),
                    TimeUnit.NANOSECONDS);
        } catch (TimeoutException timeout) {
            future.cancel(true);
            throw new TurnDeadlineExceededException();
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new LeaseLostException();
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof TurnDeadlineExceededException deadline) {
                throw deadline;
            }
            throw new LeaseLostException();
        } finally {
            executor.shutdownNow();
        }
    }
}
