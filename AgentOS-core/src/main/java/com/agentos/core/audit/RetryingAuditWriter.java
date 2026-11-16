package com.agentos.core.audit;

import com.agentos.core.config.AuditProperties;
import com.agentos.core.turn.TurnExecutionBudget;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 只重试审计写入，不拥有模型或工具回调。每次 write/confirmed 都由存储端另开短事务。
 * 超时的事务是否提交未知，只查证、不重放；明确结束的瞬时失败才允许按 200/500ms 退避。
 */
public final class RetryingAuditWriter {
    public interface Operation<T> {
        T write(Duration timeout);

        boolean confirmed(Duration timeout);
    }

    private final AuditProperties properties;
    private final Predicate<Throwable> transientFailure;
    private final Consumer<Duration> pause;

    public RetryingAuditWriter(AuditProperties properties, Predicate<Throwable> transientFailure) {
        this(
                properties,
                transientFailure,
                d -> {
                    try {
                        TimeUnit.NANOSECONDS.sleep(d.toNanos());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AuditWriteException("审计等待已取消");
                    }
                });
    }

    /** 可注入等待器只为确定性验证退避，不改变生产计时。 */
    public RetryingAuditWriter(
            AuditProperties properties,
            Predicate<Throwable> transientFailure,
            Consumer<Duration> pause) {
        this.properties = Objects.requireNonNull(properties);
        this.transientFailure = Objects.requireNonNull(transientFailure);
        this.pause = Objects.requireNonNull(pause);
    }

    public <T> T write(
            TurnExecutionBudget budget,
            boolean finalAudit,
            Operation<T> operation,
            T confirmedValue) {
        try {
            if (budget == null
                    || budget.hardStopped()
                    || (!finalAudit && (budget.expired() || budget.cancelled()))) {
                throw new AuditWriteException("审计预算不可用");
            }
            for (int attempt = 0; ; attempt++) {
                boolean cleanup = budget.expired() || budget.cancelled();
                if (cleanup && !finalAudit) {
                    throw new AuditWriteException("审计执行预算耗尽");
                }
                try {
                    return bounded(
                            budget, () -> operation.write(timeout(budget, finalAudit)), finalAudit);
                } catch (RuntimeException failure) {
                    // 提交异常后先读相同键并核对完整载荷；查询失败绝不能伪装已提交。
                    if (!budget.hardStopped()) {
                        try {
                            if (bounded(
                                    budget,
                                    () -> operation.confirmed(timeout(budget, true)),
                                    true)) {
                                return confirmedValue;
                            }
                        } catch (RuntimeException verificationFailure) {
                            throw new AuditWriteException("审计提交结果未确认");
                        }
                    }
                    if (cleanup
                            || budget.expired()
                            || budget.cancelled()
                            || failure instanceof AttemptUncertainException
                            || attempt >= properties.maxRetries()
                            || !transientFailure.test(failure)) {
                        throw new AuditWriteException("审计写入未确认");
                    }
                    var delay =
                            attempt == 0 ? properties.backoffFirst() : properties.backoffSecond();
                    if (budget.remainingNanos() <= delay.toNanos()) {
                        throw new AuditWriteException("审计重试预算不足");
                    }
                    pause.accept(delay);
                }
            }
        } catch (RuntimeException failure) {
            if (budget != null) {
                budget.auditUnconfirmed();
            }
            // 禁止把 JDBC cause、SQL 参数或凭证送进异常链和自动日志。
            throw new AuditWriteException("审计写入未确认");
        }
    }

    private Duration timeout(TurnExecutionBudget budget, boolean cleanupAllowed) {
        long remaining =
                cleanupAllowed && (budget.expired() || budget.cancelled())
                        ? budget.remainingHardStopNanos()
                        : budget.remainingNanos();
        if (remaining <= 0) {
            throw new AuditWriteException("审计预算耗尽");
        }
        return Duration.ofNanos(Math.min(properties.attemptTimeout().toNanos(), remaining));
    }

    private <T> T bounded(TurnExecutionBudget budget, Callable<T> action, boolean cleanupAllowed) {
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        Future<T> future = executor.submit(action);
        try {
            return future.get(timeout(budget, cleanupAllowed).toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new AttemptUncertainException();
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new AttemptUncertainException();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException r) {
                throw r;
            }
            throw new AuditWriteException("审计写入失败");
        } finally {
            executor.shutdownNow();
        }
    }

    private static final class AttemptUncertainException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
