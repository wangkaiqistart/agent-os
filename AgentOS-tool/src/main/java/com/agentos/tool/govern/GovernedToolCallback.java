package com.agentos.tool.govern;

import com.agentos.core.audit.AuditWriteException;
import com.agentos.core.audit.ToolInvocationRecord;
import com.agentos.core.audit.ToolInvocationRecorder;
import com.agentos.core.tool.Redactor;
import com.agentos.core.tool.SandboxViolationException;
import com.agentos.core.tool.ToolPolicy;
import com.agentos.core.tool.TransientToolException;
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
    private final int perToolLimit;
    private final int totalLimit;
    private final ToolPolicy policy;
    private final ToolInvocationRecorder recorder;
    private final Redactor redactor;
    private final JsonMapper json =
            JsonMapper.builder()
                    .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .build();

    public GovernedToolCallback(
            ToolCallback delegate,
            ToolPolicy policy,
            ToolInvocationRecorder recorder,
            Redactor redactor) {
        this(delegate, policy, recorder, redactor, 10, 30);
    }

    public GovernedToolCallback(
            ToolCallback delegate,
            ToolPolicy policy,
            ToolInvocationRecorder recorder,
            Redactor redactor,
            int perToolLimit,
            int totalLimit) {
        if (perToolLimit < -1 || totalLimit < -1) {
            throw new IllegalArgumentException("工具限次必须非负或为 -1");
        }
        this.perToolLimit = perToolLimit;
        this.totalLimit = totalLimit;
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
        String safeInput = summary(input, 10000);
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
        ToolInvocationRecord begin;
        try {
            begin = withinBudget(() -> recorder.begin(beginRecord), budget, false);
        } catch (RuntimeException failure) {
            if (failure instanceof LeaseLostException
                    || failure instanceof TurnDeadlineExceededException) {
                throw failure;
            }
            budget.auditUnconfirmed();
            throw new AuditWriteException("工具执行前的审计未确认，未执行本次工具", false);
        }
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
                if (input == null || !json.readTree(input).isObject()) {
                    throw new IllegalArgumentException();
                }
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("参数不是合法 JSON");
            }
            long toolDeadline =
                    System.nanoTime()
                            + Math.min(budget.remainingNanos(), policy.timeout().toNanos());
            for (; ; ) {
                ledger.checkpoint();
                if (budget.cancelled() || budget.expired()) {
                    throw new TurnDeadlineExceededException();
                }
                if (!budget.reserveToolAttempt(call.name(), perToolLimit, totalLimit)) {
                    // 整个模型调用未执行才是 limit_skipped；已有失败尝试时保留 failed 及实际次数。
                    status = attempts == 0 ? "limit_skipped" : "failed";
                    throw new IllegalStateException("工具调用次数达到上限");
                }
                attempts++;
                try {
                    result = attempt(input, context, turn, ledger, budget, toolDeadline);
                    break;
                } catch (TransientToolException transientFailure) {
                    if (!policy.idempotent()
                            || transientFailure.executionStarted()
                            || attempts >= 2) {
                        throw transientFailure;
                    }
                    // 重试不领取新的模型调用 ID，不重复 begin；下一次使用同一绝对截止时间。
                }
            }
            ledger.checkpoint();
            result = result == null ? "" : result;
            original = result.length();
            truncated = original > policy.maxResultChars();
            result = redactor.redact(result);
            if (truncated || result.length() > policy.maxResultChars()) {
                truncated = true;
                String marker = "\n[结果已截断，原长 " + original + " 字符]";
                int cap = policy.maxResultChars();
                result =
                        marker.length() >= cap
                                ? marker.substring(0, cap)
                                : result.substring(
                                                0, Math.min(result.length(), cap - marker.length()))
                                        + marker;
            }
            status = "success";
        } catch (RuntimeException ex) {
            if (ex instanceof TurnDeadlineExceededException) {
                status = "timeout";
            } else if (ex instanceof LeaseLostException || ex instanceof AuditWriteException) {
                status = "interrupted";
            } else if (ex instanceof ToolTimeoutException) {
                status = "timeout";
            } else if (ex instanceof SandboxViolationException) {
                status = "sandbox_denied";
            }
            error = summary(ex.getMessage() == null ? "工具内部错误" : ex.getMessage(), 1000);
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
            budget.auditUnconfirmed();
            // 超时/租约丢失是原始终止原因，不能被最后一次审计等待遮盖。
            if (failure instanceof TurnDeadlineExceededException
                    || failure instanceof LeaseLostException) {
                throw failure;
            }
            throw new AuditWriteException("工具执行后的审计未确认，操作可能已经执行");
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

    private String summary(String raw, int cap) {
        String safe = redactor.redact(raw);
        return safe == null || safe.length() <= cap ? safe : safe.substring(0, cap - 6) + "[摘要截断]";
    }

    private String attempt(
            String input,
            ToolContext context,
            TurnContext turn,
            ToolCallLedger ledger,
            TurnExecutionBudget budget,
            long toolDeadline) {
        long wait = Math.min(budget.remainingNanos(), toolDeadline - System.nanoTime());
        if (wait <= 0) {
            if (budget.expired()) {
                throw new TurnDeadlineExceededException();
            }
            throw new ToolTimeoutException();
        }
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        Future<String> future =
                executor.submit(() -> {
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
        try {
            return future.get(wait, TimeUnit.NANOSECONDS);
        } catch (TimeoutException timeout) {
            if (budget.expired()) {
                throw new TurnDeadlineExceededException();
            }
            throw new ToolTimeoutException();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (budget.expired()) {
                throw new TurnDeadlineExceededException();
            }
            throw new LeaseLostException();
        } catch (ExecutionException execution) {
            Throwable cause = execution.getCause();
            if (cause instanceof ToolExecutionException tool && tool.getCause() != null) {
                cause = tool.getCause();
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("工具内部错误");
        } finally {
            if (!future.isDone()) {
                future.cancel(true);
            }
            // 不等待不合作的 I/O，确保调用方仍能在共享 hard stop 内结束。
            executor.shutdownNow();
        }
    }

    private static final class ToolTimeoutException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ToolTimeoutException() {
            super("工具自身超时");
        }
    }

    /** 审计写入使用独立虚拟线程等待，等待上限取业务 deadline 或 hard stop 的剩余时间。 */
    private <T> T withinBudget(Callable<T> action, TurnExecutionBudget budget, boolean hardStop) {
        long remaining = hardStop ? budget.remainingHardStopNanos() : budget.remainingNanos();
        if (remaining <= 0) {
            throw new TurnDeadlineExceededException();
        }
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        boolean restoreInterrupt = hardStop && Thread.interrupted();
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
            if (restoreInterrupt) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
