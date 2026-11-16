package com.agentos.storage.audit;

import com.agentos.core.audit.AuditWriteException;
import com.agentos.core.audit.ToolInvocationRecord;
import com.agentos.core.audit.ToolInvocationRecorder;
import com.agentos.core.tool.Redactor;
import com.agentos.storage.session.ToolInvocationEntity;
import com.agentos.storage.session.ToolInvocationRepository;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * 工具意图和终态各用独立短事务提交；提交异常也在安全边界内转换，不附加数据库原始详情。
 * 唯一键是 turn/iteration/toolCallId，重复写只有身份和载荷一致才确认成功，不覆盖冲突终态。
 */
public class JpaToolInvocationRecorder implements ToolInvocationRecorder {
    private final ToolInvocationRepository repository;
    private final Redactor redactor;
    private final TransactionTemplate transaction;
    private final JsonMapper json = JsonMapper.builder().build();

    public JpaToolInvocationRecorder(
            ToolInvocationRepository repository,
            Redactor redactor,
            PlatformTransactionManager manager) {
        this.repository = repository;
        this.redactor = redactor;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public ToolInvocationRecord begin(ToolInvocationRecord record) {
        return write(() -> {
            validate(record);
            var existing = find(record);
            if (existing.isPresent()) {
                if (!sameIdentity(existing.get(), record)
                        || !"in_progress".equals(existing.get().getStatus())) {
                    throw new AuditWriteException("工具审计意图冲突");
                }
                return record;
            }
            repository.saveAndFlush(entity(record));
            return record;
        });
    }

    @Override
    public void complete(ToolInvocationRecord record) {
        write(() -> {
            validate(record);
            var row = find(record).orElseThrow(() -> new AuditWriteException("缺少工具审计意图"));
            if (!sameIdentity(row, record)) {
                throw new AuditWriteException("工具审计身份冲突");
            }
            if (!"in_progress".equals(row.getStatus())) {
                if (!sameOutcome(row, record)) {
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
        write(() -> {
            validate(record);
            var existing = find(record);
            if (existing.isPresent()) {
                if (!sameIdentity(existing.get(), record) || !sameOutcome(existing.get(), record)) {
                    throw new AuditWriteException("工具审计跳过记录冲突");
                }
            } else {
                var row = entity(record);
                apply(row, record);
                repository.saveAndFlush(row);
            }
            return null;
        });
    }

    private <T> T write(Supplier<T> operation) {
        try {
            return transaction.execute(s -> operation.get());
        } catch (RuntimeException failure) {
            throw new AuditWriteException("工具审计写入未确认，操作可能已经执行");
        }
    }

    private void validate(ToolInvocationRecord r) {
        if (r.sessionId() == null
                || r.turnId() == null
                || r.iteration() < 1
                || r.toolCallId() == null
                || r.toolCallId().isBlank()
                || !"builtin".equals(r.source())
                || r.toolName() == null
                || r.toolName().isBlank()) {
            throw new AuditWriteException("工具审计缺少关联或来源");
        }
    }

    private Optional<ToolInvocationEntity> find(ToolInvocationRecord r) {
        return repository.findByTurnIdAndIterationAndToolCallId(
                r.turnId(), r.iteration(), r.toolCallId());
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
