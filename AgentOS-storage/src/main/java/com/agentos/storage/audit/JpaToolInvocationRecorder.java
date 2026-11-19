package com.agentos.storage.audit;

import com.agentos.core.audit.AuditWriteException;
import com.agentos.core.audit.RetryingAuditWriter;
import com.agentos.core.audit.ToolInvocationRecord;
import com.agentos.core.audit.ToolInvocationRecorder;
import com.agentos.core.config.AuditProperties;
import com.agentos.core.tool.Redactor;
import com.agentos.core.turn.TurnBudgetRegistry;
import com.agentos.storage.session.ToolInvocationEntity;
import com.agentos.storage.session.ToolInvocationRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * 工具意图和终态各用独立短事务提交；提交异常也在安全边界内转换，不附加数据库原始详情。
 * 唯一键是 turn/iteration/toolCallId，重复写只有身份和载荷一致才确认成功，不覆盖冲突终态。
 */
public class JpaToolInvocationRecorder implements ToolInvocationRecorder {
    private final ToolInvocationRepository repository;
    private final Redactor redactor;
    private static final Logger log = LoggerFactory.getLogger(JpaToolInvocationRecorder.class);
    private final AuditTransactions transactions;
    private final TurnBudgetRegistry budgets;
    private final RetryingAuditWriter writer;
    private final JsonMapper json = JsonMapper.builder().build();

    public JpaToolInvocationRecorder(
            ToolInvocationRepository repository,
            Redactor redactor,
            AuditTransactions transactions,
            TurnBudgetRegistry budgets,
            AuditProperties properties) {
        this.repository = repository;
        this.redactor = redactor;
        this.transactions = transactions;
        this.budgets = budgets;
        writer = new RetryingAuditWriter(properties, AuditTransactions::transientFailure);
    }

    @Override
    public ToolInvocationRecord begin(ToolInvocationRecord record) {
        return write(
                record,
                false,
                () -> {
                    validate(record);
                    // 正常意图直接插入；唯一约束及失败后的完整查证处理重复调用/冲突。
                    repository.saveAndFlush(entity(record));
                    return record;
                });
    }

    @Override
    public void complete(ToolInvocationRecord record) {
        write(
                record,
                true,
                () -> {
                    validate(record);
                    var row = find(record).orElseThrow(() -> new AuditWriteException("缺少工具审计意图"));
                    if (!sameIdentity(row, record)) {
                        throw new AuditWriteException("工具审计身份冲突");
                    }
                    if (!"in_progress".equals(row.getStatus())) {
                        if (!sameOutcome(row, record) && !recoveredOutcome(row, record)) {
                            throw new AuditWriteException("工具审计终态冲突");
                        }
                        return null;
                    }
                    apply(row, record);
                    repository.saveAndFlush(row);
                    return null;
                });
    }

    @Override
    public void skipped(ToolInvocationRecord record) {
        write(
                record,
                false,
                () -> {
                    validate(record);
                    var row = entity(record);
                    apply(row, record);
                    repository.saveAndFlush(row);
                    return null;
                });
    }

    private <T> T write(ToolInvocationRecord record, boolean finalAudit, Supplier<T> operation) {
        try {
            validate(record);
            @SuppressWarnings("unchecked")
            T confirmedValue = "in_progress".equals(record.status()) ? (T) record : null;
            return writer.write(
                    budgets.get(record.turnId()),
                    finalAudit,
                    new RetryingAuditWriter.Operation<T>() {
                        @Override
                        public T write(Duration timeout) {
                            return transactions.execute(
                                    timeout,
                                    () -> {
                                        if (!finalAudit) {
                                            transactions.requireRunning(
                                                    record.sessionId(), record.turnId());
                                        }
                                        return operation.get();
                                    });
                        }

                        @Override
                        public boolean confirmed(Duration timeout) {
                            return transactions.execute(
                                    timeout,
                                    () ->
                                            find(record)
                                                    .map(row -> {
                                                        if (!sameIdentity(row, record)
                                                                || (!sameOutcome(row, record)
                                                                        && !recoveredOutcome(
                                                                                row, record))) {
                                                            throw new AuditWriteException(
                                                                    "工具审计载荷冲突");
                                                        }
                                                        return true;
                                                    })
                                                    .orElse(false));
                        }
                    },
                    confirmedValue);
        } catch (RuntimeException failure) {
            if (failure instanceof com.agentos.core.turn.LeaseLostException
                    || failure instanceof com.agentos.core.turn.TurnDeadlineExceededException) {
                throw failure;
            }
            var budget = budgets.get(record.turnId());
            if (budget != null) {
                budget.auditUnconfirmed();
            }
            if (finalAudit) {
                markUnconfirmed(record);
            }
            // 仅记关联和固定文案，不输出 SQL、参数、Throwable 或数据库地址。
            var previous = org.slf4j.MDC.getCopyOfContextMap();
            org.slf4j.MDC.clear();
            try {
                log.atError()
                        .addKeyValue("sessionId", record.sessionId())
                        .addKeyValue("turnId", record.turnId())
                        .addKeyValue("iteration", record.iteration())
                        .addKeyValue(
                                "agent",
                                previous == null ? null : redactor.redact(previous.get("agent")))
                        .addKeyValue("persistenceStatus", "UNCONFIRMED")
                        .log("工具审计未确认");
            } finally {
                if (previous != null) {
                    org.slf4j.MDC.setContextMap(previous);
                }
            }
            throw new AuditWriteException(
                    finalAudit ? "工具执行后的审计未确认，操作可能已经执行" : "工具执行前的审计未确认，未执行本次工具", finalAudit);
        }
    }

    /** 完成写入无法查证时，仅在原 hard stop 内尽力标记；永不覆盖已确认或已回收的终态。 */
    private void markUnconfirmed(ToolInvocationRecord record) {
        var budget = budgets.get(record.turnId());
        if (budget == null || budget.hardStopped()) {
            return;
        }
        try {
            writer.write(
                    budget,
                    true,
                    new RetryingAuditWriter.Operation<Void>() {
                        @Override
                        public Void write(Duration timeout) {
                            return transactions.execute(
                                    timeout,
                                    () -> {
                                        var row =
                                                find(record)
                                                        .orElseThrow(() ->
                                                                new AuditWriteException(
                                                                        "缺少工具审计意图"));
                                        if (!sameIdentity(row, record)) {
                                            throw new AuditWriteException("工具审计身份冲突");
                                        }
                                        if ("in_progress".equals(row.getStatus())) {
                                            row.complete(
                                                    "unconfirmed",
                                                    null,
                                                    record.resultChars(),
                                                    record.resultTruncated(),
                                                    "工具执行后的审计未确认，操作可能已经执行",
                                                    record.attempts(),
                                                    record.durationMs(),
                                                    Instant.now());
                                            repository.saveAndFlush(row);
                                        }
                                        return null;
                                    });
                        }

                        @Override
                        public boolean confirmed(Duration timeout) {
                            return transactions.execute(
                                    timeout,
                                    () ->
                                            find(record)
                                                    .map(row ->
                                                            sameIdentity(row, record)
                                                                    && !"in_progress"
                                                                            .equals(
                                                                                    row
                                                                                            .getStatus()))
                                                    .orElse(false));
                        }
                    },
                    null);
        } catch (RuntimeException unavailable) {
            // 数据库持续不可用时保留未确认事实，由回收路径处理原 in_progress；不得重跑工具。
        }
    }

    private void validate(ToolInvocationRecord r) {
        if (r.sessionId() == null
                || r.turnId() == null
                || r.iteration() < 1
                || r.toolCallId() == null
                || r.toolCallId().isBlank()
                || r.toolName() == null
                || r.toolName().isBlank()) {
            throw new AuditWriteException("工具审计缺少关联或来源");
        }
        try {
            com.agentos.core.tool.ToolDescriptor.requireSource(r.source());
        } catch (IllegalArgumentException invalid) {
            throw new AuditWriteException("工具审计来源不合法");
        }
    }

    private Optional<ToolInvocationEntity> find(ToolInvocationRecord r) {
        return repository.findForUpdate(r.turnId(), r.iteration(), r.toolCallId());
    }

    private Object input(String raw) {
        String safe = redactor.redact(raw);
        try {
            return json.readValue(safe, Object.class);
        } catch (RuntimeException invalid) {
            return safe;
        }
    }

    private ToolInvocationEntity entity(ToolInvocationRecord r) {
        return new ToolInvocationEntity(
                r.sessionId(),
                r.turnId(),
                r.iteration(),
                r.toolCallId(),
                r.toolName(),
                r.source(),
                input(r.inputJson()),
                r.idempotent(),
                Instant.now());
    }

    private boolean sameIdentity(ToolInvocationEntity row, ToolInvocationRecord r) {
        return row.getSessionId().equals(r.sessionId())
                && row.getToolName().equals(r.toolName())
                && row.getToolSource().equals(r.source())
                && row.isIdempotent() == r.idempotent()
                && Objects.equals(row.getInputJson(), input(r.inputJson()));
    }

    private boolean recoveredOutcome(ToolInvocationEntity row, ToolInvocationRecord record) {
        // recovery 的 interrupted 表示结果未知；身份和数据库终态均已确认时，迟到 complete 不再覆盖它。
        return "interrupted".equals(row.getStatus())
                && transactions.recoveredTurn(record.sessionId(), record.turnId());
    }

    private boolean sameOutcome(ToolInvocationEntity row, ToolInvocationRecord r) {
        return row.getStatus().equals(r.status())
                && Objects.equals(row.getResultText(), redactor.redact(r.resultText()))
                && Objects.equals(row.getResultChars(), r.resultChars())
                && row.isResultTruncated() == r.resultTruncated()
                && Objects.equals(row.getErrorMessage(), redactor.redact(r.errorMessage()))
                && row.getAttempts() == r.attempts()
                && Objects.equals(row.getDurationMs(), r.durationMs());
    }

    private void apply(ToolInvocationEntity row, ToolInvocationRecord r) {
        row.complete(
                r.status(),
                redactor.redact(r.resultText()),
                r.resultChars(),
                r.resultTruncated(),
                redactor.redact(r.errorMessage()),
                r.attempts(),
                r.durationMs(),
                Instant.now());
    }
}
