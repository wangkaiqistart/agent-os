package com.agentos.tool.govern;

import com.agentos.core.audit.AuditWriteException;
import com.agentos.core.audit.ToolInvocationRecord;
import com.agentos.core.audit.ToolInvocationRecorder;
import com.agentos.core.tool.Redactor;
import com.agentos.core.tool.SandboxViolationException;
import com.agentos.core.tool.ToolCleanupUnconfirmedException;
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

/**
 * 每个注册工具都经过此装饰器：检查租约、领取模型调用 ID、确认审计意图，再执行副作用。
 * 不负责选择工具或循环调用；这些工作仍由唯一注入的官方 Manager 完成。
 */
public final class GovernedToolCallback implements ToolCallback {
    // 工具内部热循环（逐行搜索、输出排空、10ms 等待轮询）最多每秒续租一次，避免每次检查都发起数据库事务；
    // 租约 TTL 远大于该间隔，治理边界的检查点仍每次续租。
    private static final java.time.Duration IN_TOOL_RENEW_INTERVAL =
            java.time.Duration.ofSeconds(1);
    private static final long WAIT_SLICE_NANOS = TimeUnit.MILLISECONDS.toNanos(100);
    private com.agentos.core.tool.ToolDescriptor registration;
    private String source = "builtin";
    private final com.agentos.tool.registry.ToolDefinitionValidator validator =
            new com.agentos.tool.registry.ToolDefinitionValidator();

    public GovernedToolCallback(
            com.agentos.core.tool.ToolDescriptor descriptor,
            ToolInvocationRecorder recorder,
            Redactor redactor,
            int perToolLimit,
            int totalLimit) {
        this(
                descriptor.callback(),
                descriptor.policy(),
                recorder,
                redactor,
                perToolLimit,
                totalLimit);
        this.registration = descriptor;
        this.source = descriptor.source();
    }

    private final ToolCallback delegate;
    private final int perToolLimit;
    private final int totalLimit;
    private final ToolPolicy policy;
    private final ToolInvocationRecorder recorder;
    private final Redactor redactor;

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

    public GovernedToolCallback(
            ToolCallback delegate,
            ToolPolicy policy,
            ToolInvocationRecorder recorder,
            Redactor redactor,
            int perToolLimit,
            int totalLimit,
            String source) {
        this(delegate, policy, recorder, redactor, perToolLimit, totalLimit);
        com.agentos.core.tool.ToolDescriptor.requireSource(source);
        this.source = source;
    }

    public com.agentos.core.tool.ToolDescriptor descriptor() {
        var definition = getToolDefinition();
        return new com.agentos.core.tool.ToolDescriptor(
                definition.name(),
                definition.description(),
                definition.inputSchema(),
                source,
                registration == null ? null : registration.originalName(),
                policy,
                getToolMetadata(),
                this,
                registration == null ? delegate : registration.registrationIdentity(),
                registration == null
                        ? com.agentos.core.tool.ToolDescriptor.Availability.AVAILABLE
                        : registration.availability(),
                registration == null ? 0 : registration.generation());
    }

    @Override
    public ToolDefinition getToolDefinition() {
        var definition = delegate.getToolDefinition();
        return ToolDefinition.builder()
                .name(definition.name())
                .description(redactor.summarize(definition.description(), 4000))
                .inputSchema(definition.inputSchema())
                .build();
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
        var snapshot =
                (com.agentos.core.tool.ToolCatalogSnapshot)
                        context.getContext().get(ToolContextKeys.TOOL_SNAPSHOT);
        String actualSource = snapshot == null ? source : snapshot.sourceOf(call.name());
        // begin 的正常返回是执行许可；失败时不会创建任务，更不会调用真实工具。
        var beginRecord =
                new ToolInvocationRecord(
                        turn.sessionId(),
                        turn.turnId(),
                        turn.iteration(),
                        call.id(),
                        call.name(),
                        actualSource,
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
        var scope =
                new com.agentos.core.tool.ToolExecutionScope(
                        turn,
                        call.id(),
                        budget,
                        policy.timeout(),
                        ledger::checkpoint,
                        IN_TOOL_RENEW_INTERVAL);
        var values = new java.util.HashMap<String, Object>(context.getContext());
        values.put(ToolContextKeys.TOOL_SCOPE, scope);
        var scopedContext = new ToolContext(java.util.Map.copyOf(values));
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
            validator.validateInput(getToolDefinition().inputSchema(), input);
            if (snapshot != null && !snapshot.declaredTools().contains(call.name())) {
                throw new IllegalArgumentException("本 Agent 未授权该工具");
            }
            if (registration != null
                    && registration.availability()
                            != com.agentos.core.tool.ToolDescriptor.Availability.AVAILABLE) {
                throw new IllegalStateException(
                        registration.availability()
                                        == com.agentos.core.tool.ToolDescriptor.Availability
                                                .UNCONFIGURED
                                ? "UNCONFIGURED：工具未配置"
                                : "UNAVAILABLE：工具来源不可用");
            }
            for (; ; ) {
                scope.checkpointAndRenew();
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
                    result = attempt(input, scopedContext, turn, ledger, budget, scope);
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
            result = result == null ? "" : result;
            original = result.length();
            truncated = original > policy.maxResultChars();
            result = redactor.redact(result);
            if (truncated || result.length() > policy.maxResultChars()) {
                truncated = true;
                result = StructuredToolResult.limit(result, policy.maxResultChars(), original);
            }
            // 先治理已取得的结果，再检查租约/预算；检查失败的完成审计也不能携带原始长正文。
            scope.checkpointAndRenew();
            scope.completed();
            status = "success";
        } catch (RuntimeException ex) {
            if (ex instanceof TurnDeadlineExceededException) {
                status = "timeout";
            } else if (ex instanceof LeaseLostException || ex instanceof AuditWriteException) {
                status = "interrupted";
            } else if (ex
                    instanceof com.agentos.core.tool.ToolExecutionScope.ToolTimeoutException) {
                status = "timeout";
            } else if (ex instanceof SandboxViolationException) {
                status = "sandbox_denied";
            } else if (ex instanceof ToolCleanupUnconfirmedException) {
                status = "unconfirmed";
            }
            error = summary(ex.getMessage() == null ? "工具内部错误" : ex.getMessage(), 1000);
            failure = ex;
        }
        scope.close();
        scope.awaitWorkerCleanup();
        if (!scope.cleanupConfirmed()) {
            var cleanupFailure = new ToolCleanupUnconfirmedException();
            error = cleanupFailure.getMessage();
            // 清理未确认使普通业务失败也升级为终止信号；既有致命原因保持优先级。
            if (!(failure instanceof AuditWriteException)
                    && !(failure instanceof LeaseLostException)
                    && !(failure instanceof TurnDeadlineExceededException)) {
                failure = cleanupFailure;
            }
            if (status.equals("success")) {
                status = "unconfirmed";
            }
        } else if (failure != null
                && scope.executionState()
                        == com.agentos.core.tool.ToolExecutionScope.ExecutionState
                                .POSSIBLY_STARTED) {
            error = summary(error + "；操作可能已经执行", 1000);
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
                    true,
                    scope.remainingCleanupNanos());
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
                || failure instanceof TurnDeadlineExceededException
                || failure instanceof ToolCleanupUnconfirmedException) {
            throw failure;
        }
        if (failure != null) {
            throw new ToolExecutionException(
                    getToolDefinition(),
                    new IllegalStateException(
                            redactor.redact("工具 " + call.name() + " 执行失败：" + error + "；本轮不自动重试")));
        }
        scope.confirmSubmission();
        return result;
    }

    private String summary(String raw, int cap) {
        return redactor.summarize(raw, cap);
    }

    private String attempt(
            String input,
            ToolContext context,
            TurnContext turn,
            ToolCallLedger ledger,
            TurnExecutionBudget budget,
            com.agentos.core.tool.ToolExecutionScope scope) {
        long wait = Math.min(budget.remainingNanos(), scope.remainingNanos());
        if (wait <= 0) {
            if (budget.expired()) {
                throw new TurnDeadlineExceededException();
            }
            throw new com.agentos.core.tool.ToolExecutionScope.ToolTimeoutException();
        }
        var finished = new java.util.concurrent.CountDownLatch(1);
        var future =
                new java.util.concurrent.FutureTask<String>(() -> {
                    MDC.put("sessionId", turn.sessionId().toString());
                    MDC.put("turnId", turn.turnId().toString());
                    MDC.put("agent", turn.agent().profile().name());
                    MDC.put("iteration", Integer.toString(turn.iteration()));
                    try {
                        scope.possiblyStarted();
                        return delegate.call(input, context);
                    } finally {
                        MDC.clear();
                        finished.countDown();
                    }
                });
        var thread = Thread.ofVirtual().unstarted(future);
        var worker =
                scope.registerWorker(
                        thread,
                        deadline -> {
                            scope.cancelWorker(thread, () -> future.cancel(true));
                            return finished.await(
                                    Math.max(0, deadline - System.nanoTime()),
                                    TimeUnit.NANOSECONDS);
                        });
        try {
            thread.start();
            // 等待人工授权时工具与整轮计时会暂停，所以分段等待并每段重读余量。
            while (true) {
                try {
                    return future.get(
                            Math.max(1, Math.min(wait, WAIT_SLICE_NANOS)), TimeUnit.NANOSECONDS);
                } catch (TimeoutException slice) {
                    wait = Math.min(budget.remainingNanos(), scope.remainingNanos());
                    if (wait <= 0) {
                        if (budget.expired()) {
                            throw new TurnDeadlineExceededException();
                        }
                        throw new com.agentos.core.tool.ToolExecutionScope.ToolTimeoutException();
                    }
                }
            }
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
                scope.cancelWorker(thread, () -> future.cancel(true));
            }
            // 不等待不合作的 I/O，确保调用方仍能在共享 hard stop 内结束。
            if (finished.getCount() == 0) {
                worker.close();
            }
        }
    }

    /** 审计写入使用独立虚拟线程等待，等待上限取业务 deadline 或 hard stop 的剩余时间。 */
    private <T> T withinBudget(Callable<T> action, TurnExecutionBudget budget, boolean hardStop) {
        return withinBudget(action, budget, hardStop, Long.MAX_VALUE);
    }

    private <T> T withinBudget(
            Callable<T> action, TurnExecutionBudget budget, boolean hardStop, long cap) {
        long remaining =
                Math.min(cap, hardStop ? budget.remainingHardStopNanos() : budget.remainingNanos());
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
