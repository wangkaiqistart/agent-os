package com.agentos.tool.govern;

import com.agentos.core.audit.AuditWriteException;
import com.agentos.core.audit.ToolInvocationRecord;
import com.agentos.core.audit.ToolInvocationRecorder;
import com.agentos.core.tool.Redactor;
import com.agentos.core.tool.SandboxViolationException;
import com.agentos.core.tool.ToolPolicy;
import com.agentos.core.turn.LeaseLostException;
import com.agentos.core.turn.ToolCallLedger;
import com.agentos.core.turn.ToolContextKeys;
import com.agentos.core.turn.TurnContext;
import com.agentos.core.turn.TurnDeadlineExceededException;
import com.agentos.core.turn.TurnExecutionBudget;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.MDC;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.metadata.ToolMetadata;
import tools.jackson.databind.json.JsonMapper;

/**
 * 每个注册工具都经过此装饰器：检查租约、领取模型调用 ID、确认审计意图，再执行副作用。
 * 不负责选择工具或循环调用；这些工作仍由唯一注入的官方 Manager 完成。
 */
public final class GovernedToolCallback implements ToolCallback {
    private final ToolCallback delegate;
    private final ToolPolicy policy;
    private final ToolInvocationRecorder recorder;
    private final Redactor redactor;
    private final JsonMapper json = JsonMapper.builder().build();

    public GovernedToolCallback(
            ToolCallback delegate,
            ToolPolicy policy,
            ToolInvocationRecorder recorder,
            Redactor redactor) {
        this.delegate = delegate;
        this.policy = policy;
        this.recorder = recorder;
        this.redactor = redactor;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String input) {
        throw new IllegalStateException("治理工具必须带显式 ToolContext");
    }

    @Override
    public String call(String input, ToolContext context) {
        if (context == null) {
            throw new IllegalStateException("治理工具必须带显式 ToolContext");
        }
        var turn = (TurnContext) context.getContext().get(ToolContextKeys.TURN_CONTEXT);
        var ledger = (ToolCallLedger) context.getContext().get(ToolContextKeys.TOOL_CALL_LEDGER);
        var budget = (TurnExecutionBudget) context.getContext().get(ToolContextKeys.TURN_BUDGET);
        ledger.checkpoint();
        var call = ledger.next(getToolDefinition().name());
        String safeInput = redactor.redact(input);
        // begin 的正常返回是执行许可；失败时不会创建任务，更不会调用真实工具。
        var beginRecord =
                new ToolInvocationRecord(
                        turn.sessionId(),
                        turn.turnId(),
                        turn.iteration(),
                        call.id(),
                        call.name(),
                        "builtin",
                        safeInput,
                        null,
                        null,
                        false,
                        "in_progress",
                        null,
                        policy.idempotent(),
                        0,
                        null);
        var begin = withinBudget(() -> recorder.begin(beginRecord), budget, false);
        long started = System.nanoTime();
        int attempts = 0;
        String status = "failed";
        String result = null;
        String error = null;
        Integer original = null;
        boolean truncated = false;
        RuntimeException failure = null;
        try {
            ledger.checkpoint();
            try {
                if (input == null || json.readTree(input) == null) {
                    throw new IllegalArgumentException();
                }
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("参数不是合法 JSON");
            }
            long remaining = budget.remainingNanos();
            boolean wholeTurnFirst = remaining <= policy.timeout().toNanos();
            long wait = Math.min(remaining, policy.timeout().toNanos());
            if (wait <= 0) {
                throw new TurnDeadlineExceededException();
            }
            var executor = Executors.newVirtualThreadPerTaskExecutor();
            Future<String> future = null;
            try {
                attempts = 1;
                future =
                        executor.submit(() -> {
                            // 虚拟线程不继承业务身份；只从显式 TurnContext 重建日志关联。
                            MDC.put("sessionId", turn.sessionId().toString());
                            MDC.put("turnId", turn.turnId().toString());
                            MDC.put("agent", turn.agent().profile().name());
                            MDC.put("iteration", Integer.toString(turn.iteration()));
                            try {
                                ledger.checkpoint();
                                return delegate.call(input, context);
                            } finally {
                                MDC.clear();
                            }
                        });
                result = future.get(wait, TimeUnit.NANOSECONDS);
            } catch (TimeoutException timeout) {
                status = "timeout";
                if (wholeTurnFirst || budget.expired()) {
                    throw new TurnDeadlineExceededException();
                }
                throw new IllegalStateException("工具自身超时");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                if (budget.expired()) {
                    throw new TurnDeadlineExceededException();
                }
                throw new LeaseLostException();
            } catch (ExecutionException execution) {
                Throwable cause = execution.getCause();
                // 官方 @Tool 方法适配器会包装反射目标异常；恢复业务类型后才能正确区分沙箱与致命信号。
                if (cause instanceof ToolExecutionException tool && tool.getCause() != null) {
                    cause = tool.getCause();
                }
                if (cause instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new IllegalStateException("工具内部错误");
            } finally {
                if (future != null && !future.isDone()) {
                    future.cancel(true);
                }
                // 不使用 ExecutorService.close：不合作的 I/O 不能无限拖住整轮 hard stop。
                executor.shutdownNow();
            }
            ledger.checkpoint();
            result = result == null ? "" : result;
            original = result.length();
            truncated = original > policy.maxResultChars();
            if (truncated) {
                result =
                        result.substring(0, policy.maxResultChars())
                                + "\n[结果已截断，原长 "
                                + original
                                + " 字符]";
            }
            result = redactor.redact(result);
            status = "success";
        } catch (RuntimeException ex) {
            if (ex instanceof TurnDeadlineExceededException) {
                status = "timeout";
            } else if (ex instanceof LeaseLostException || ex instanceof AuditWriteException) {
                status = "interrupted";
            } else if (ex instanceof SandboxViolationException) {
                status = "sandbox_denied";
            }
            error = redactor.redact(ex.getMessage() == null ? "工具内部错误" : ex.getMessage());
            failure = ex;
        }
        var end =
                new ToolInvocationRecord(
                        begin.sessionId(),
                        begin.turnId(),
                        begin.iteration(),
                        begin.toolCallId(),
                        begin.toolName(),
                        begin.source(),
                        safeInput,
                        result,
                        original,
                        truncated,
                        status,
                        error,
                        policy.idempotent(),
                        attempts,
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        // 结果写入失败也必须中止 Manager；操作可能已发生，绝不能因审计失败重试副作用。
        try {
            withinBudget(
                    () -> {
                        recorder.complete(end);
                        return null;
                    },
                    budget,
                    true);
        } catch (RuntimeException completionFailure) {
            // 超时/租约丢失是原始终止原因，不能被最后一次审计等待遮盖。
            if (failure instanceof TurnDeadlineExceededException
                    || failure instanceof LeaseLostException) {
                throw failure;
            }
            throw completionFailure;
        }
        if (failure instanceof AuditWriteException
                || failure instanceof LeaseLostException
                || failure instanceof TurnDeadlineExceededException) {
            throw failure;
        }
        if (failure != null) {
            throw new ToolExecutionException(
                    getToolDefinition(),
                    new IllegalStateException(
                            redactor.redact("工具 " + call.name() + " 执行失败：" + error + "；本轮不自动重试")));
        }
        return result;
    }

    /** 审计写入使用独立虚拟线程等待，等待上限取业务 deadline 或 hard stop 的剩余时间。 */
    private <T> T withinBudget(Callable<T> action, TurnExecutionBudget budget, boolean hardStop) {
        long remaining = hardStop ? budget.remainingHardStopNanos() : budget.remainingNanos();
        if (remaining <= 0) {
            throw new TurnDeadlineExceededException();
        }
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        Future<T> future = executor.submit(action);
        try {
            return future.get(Math.max(1, remaining), TimeUnit.NANOSECONDS);
        } catch (TimeoutException timeout) {
            future.cancel(true);
            throw new TurnDeadlineExceededException();
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new LeaseLostException();
        } catch (ExecutionException execution) {
            Throwable cause = execution.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new AuditWriteException("工具审计写入未确认，操作可能已经执行");
        } finally {
            executor.shutdownNow();
        }
    }
}
