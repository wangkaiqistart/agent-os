package com.agentos.storage.audit;

import com.agentos.core.audit.AuditWriteException;
import com.agentos.core.audit.RetryingAuditWriter;
import com.agentos.core.config.AuditProperties;
import com.agentos.core.provider.LlmCallRecord;
import com.agentos.core.provider.LlmCallRecorder;
import com.agentos.core.tool.Redactor;
import com.agentos.core.turn.TurnBudgetRegistry;
import com.agentos.storage.session.LlmCallEntity;
import com.agentos.storage.session.LlmCallRepository;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 只重试持久化，不重新调用模型；一次 record 对应一个随机 write_id。 */
public class JpaLlmCallRecorder implements LlmCallRecorder {
    private static final Logger log = LoggerFactory.getLogger(JpaLlmCallRecorder.class);
    private final LlmCallRepository repository;
    private final AuditTransactions transactions;
    private final TurnBudgetRegistry budgets;
    private final Redactor redactor;
    private final RetryingAuditWriter writer;
    private final Duration attemptTimeout;

    public JpaLlmCallRecorder(
            LlmCallRepository repository,
            AuditTransactions transactions,
            TurnBudgetRegistry budgets,
            Redactor redactor,
            AuditProperties properties) {
        this.repository = repository;
        this.transactions = transactions;
        this.budgets = budgets;
        this.redactor = redactor;
        writer = new RetryingAuditWriter(properties, AuditTransactions::transientFailure);
        attemptTimeout = properties.attemptTimeout();
    }

    @Override
    public void record(LlmCallRecord original) {
        UUID writeId = UUID.randomUUID();
        LlmCallRecord record = safe(original);
        try {
            if (record.sessionId() == null
                    || record.turnId() == null
                    || record.iteration() == null) {
                throw new AuditWriteException("模型审计缺少关联");
            }
            writer.write(
                    budgets.get(record.turnId()),
                    true,
                    new RetryingAuditWriter.Operation<Void>() {
                        @Override
                        public Void write(Duration timeout) {
                            return transactions.execute(
                                    timeout,
                                    () -> {
                                        // 新 record 的随机 write_id 不需要先查存在性。
                                        // 唯一约束阻止重复写；异常后的 confirmed 按原键核对全部载荷，
                                        // 未知提交不重放，确认冲突也不能当作暂时未写入而重试。
                                        repository.saveAndFlush(new LlmCallEntity(writeId, record));
                                        return null;
                                    });
                        }

                        @Override
                        public boolean confirmed(Duration timeout) {
                            return transactions.execute(
                                    timeout,
                                    () ->
                                            repository
                                                    .findByWriteId(writeId)
                                                    .map(row -> {
                                                        if (!row.toRecord().equals(record)) {
                                                            throw new AuditWriteException(
                                                                    "模型审计载荷冲突");
                                                        }
                                                        return true;
                                                    })
                                                    .orElse(false));
                        }
                    },
                    null);
        } catch (RuntimeException failure) {
            emit(record, writeId, false);
            throw new AuditWriteException(
                    record.sessionId() == null
                                    || record.turnId() == null
                                    || record.iteration() == null
                            ? "llm_calls 缺少 session、turn 或 iteration 关联"
                            : "模型审计写入未确认，模型请求不会重发");
        }
        // 仅确认落库后输出一次，SQL 重试不会重复输出成功日志。
        emit(record, writeId, true);
    }

    /** PostgreSQL timestamp 精度为微秒；写入与查证使用同一规整后的全部字段。 */
    private LlmCallRecord safe(LlmCallRecord r) {
        return new LlmCallRecord(
                r.sessionId(),
                r.turnId(),
                r.iteration(),
                redactor.redact(r.provider()),
                redactor.redact(r.requestedModel()),
                redactor.redact(r.responseModel()),
                redactor.redact(r.responseId()),
                r.mode(),
                r.attempt(),
                redactor.redact(r.finishReason()),
                r.toolCallCount(),
                r.imageCount(),
                r.promptTokens(),
                r.completionTokens(),
                r.totalTokens(),
                r.cacheReadTokens(),
                r.cacheWriteTokens(),
                r.requestsRemaining(),
                r.tokensRemaining(),
                r.outputCapHonored(),
                r.success(),
                r.failureKind(),
                r.httpStatus(),
                redactor.redact(r.errorSummary()),
                r.startedAt().truncatedTo(ChronoUnit.MICROS),
                r.durationMs());
    }

    private void emit(LlmCallRecord record, UUID writeId, boolean confirmed) {
        // 这里只保存/恢复日志上下文，不从 MDC 获取任何业务身份。结构化关联全部来自 record。
        // Boot JSON 不接受 MDC 与 key-value 的同名键，隔离后也避免附带未知敏感 MDC。
        var previous = org.slf4j.MDC.getCopyOfContextMap();
        org.slf4j.MDC.clear();
        if (previous != null && previous.get("agent") != null) {
            org.slf4j.MDC.put("agent", redactor.redact(previous.get("agent")));
        }
        try {
            var event =
                    (!confirmed ? log.atError() : record.success() ? log.atInfo() : log.atWarn())
                            .addKeyValue("event", "llm_call")
                            .addKeyValue("write_id", writeId.toString())
                            .addKeyValue(
                                    "persistenceStatus", confirmed ? "CONFIRMED" : "UNCONFIRMED")
                            .addKeyValue("sessionId", text(record.sessionId()))
                            .addKeyValue("turnId", text(record.turnId()))
                            .addKeyValue("iteration", record.iteration())
                            .addKeyValue("provider", record.provider())
                            .addKeyValue("requestedModel", record.requestedModel())
                            .addKeyValue("responseModel", record.responseModel())
                            .addKeyValue("responseId", record.responseId())
                            .addKeyValue("mode", text(record.mode()))
                            .addKeyValue("attempt", record.attempt())
                            .addKeyValue("finishReason", record.finishReason())
                            .addKeyValue("toolCallCount", record.toolCallCount())
                            .addKeyValue("imageCount", record.imageCount())
                            .addKeyValue("promptTokens", record.promptTokens())
                            .addKeyValue("completionTokens", record.completionTokens())
                            .addKeyValue("totalTokens", record.totalTokens())
                            .addKeyValue("cacheReadTokens", record.cacheReadTokens())
                            .addKeyValue("cacheWriteTokens", record.cacheWriteTokens())
                            .addKeyValue("requestsRemaining", record.requestsRemaining())
                            .addKeyValue("tokensRemaining", record.tokensRemaining())
                            .addKeyValue("outputCapHonored", record.outputCapHonored())
                            .addKeyValue("success", record.success())
                            .addKeyValue("failureKind", text(record.failureKind()))
                            .addKeyValue("httpStatus", record.httpStatus())
                            .addKeyValue("errorSummary", record.errorSummary())
                            .addKeyValue("startedAt", text(record.startedAt()))
                            .addKeyValue("durationMs", record.durationMs());
            if (confirmed) {
                event.log("模型调用审计 {}", record.summary());
            } else {
                // 默认控制台格式不显示 key-value，正文也提供安全关联与配置上限，便于现场定位。
                event.log(
                        "模型审计写入未确认，turnId={}，attemptTimeoutMs={}，persistenceStatus=UNCONFIRMED",
                        record.turnId(),
                        attemptTimeout.toMillis());
            }
        } finally {
            if (previous != null) {
                org.slf4j.MDC.setContextMap(previous);
            }
        }
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }
}
