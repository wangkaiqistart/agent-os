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

/**
 * 将每一次模型调用尝试的结构化摘要写入 llm_calls，成功和失败都应有记录。
 *
 * <p>事务只包住审计写入，不包住模型 HTTP 请求。此处依赖上游提供脱敏摘要，
 * 不保存完整 Prompt 或答案，也不在本类重新执行模型请求。
 */
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
        // 相同摘要生成稳定键，唯一约束能识别重复；当前不是 upsert，重复写入会报错，
        // 也没有在这里实现自动重试或“重复即成功”的幂等确认。
        UUID writeId = stableWriteId(record);
        try {
            // flush 尽早暴露约束错误；最终提交仍由事务代理完成，提交阶段也可能失败。
            repository.saveAndFlush(new LlmCallEntity(writeId, record));
        } catch (RuntimeException error) {
            throw new AuditWriteException("写入模型调用审计失败", error);
        }
    }

    /** 用业务关联和本次尝试的摘要生成去重标识；不是用于鉴权或加密的随机秘密。 */
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
