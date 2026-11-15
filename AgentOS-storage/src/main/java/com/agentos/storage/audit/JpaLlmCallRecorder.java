package com.agentos.storage.audit;

import com.agentos.core.audit.AuditWriteException;
import com.agentos.core.provider.LlmCallRecord;
import com.agentos.core.provider.LlmCallRecorder;
import com.agentos.storage.session.LlmCallEntity;
import com.agentos.storage.session.LlmCallRepository;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/** 将 Provider 的结构化调用记录写入 llm_calls。 */
public class JpaLlmCallRecorder implements LlmCallRecorder {
    private final LlmCallRepository repository;

    public JpaLlmCallRecorder(LlmCallRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Override
    @Transactional
    public void record(LlmCallRecord record) {
        if (record.sessionId() == null || record.turnId() == null || record.iteration() == null) {
            throw new AuditWriteException("llm_calls 缺少 session、turn 或 iteration 关联");
        }
        // 记录入口生成稳定键；数据库提交结果不确定时，重试同一 payload 仍命中同一行。
        UUID writeId = stableWriteId(record);
        try {
            repository.saveAndFlush(new LlmCallEntity(writeId, record));
        } catch (RuntimeException error) {
            throw new AuditWriteException("写入模型调用审计失败", error);
        }
    }

    private static UUID stableWriteId(LlmCallRecord record) {
        String payload =
                String.join(
                        "|",
                        String.valueOf(record.sessionId()),
                        String.valueOf(record.turnId()),
                        String.valueOf(record.iteration()),
                        String.valueOf(record.attempt()),
                        String.valueOf(record.startedAt()),
                        String.valueOf(record.provider()),
                        String.valueOf(record.requestedModel()),
                        String.valueOf(record.success()),
                        String.valueOf(record.errorSummary()));
        return UUID.nameUUIDFromBytes(payload.getBytes(StandardCharsets.UTF_8));
    }
}
