-- 保持已发布 V1 的校验和，通过增量迁移扩展审计状态，保留全部历史记录。
ALTER TABLE tool_invocations DROP CONSTRAINT tool_invocations_status_check;

ALTER TABLE tool_invocations ADD CONSTRAINT tool_invocations_status_check
    CHECK (status IN ('in_progress', 'success', 'failed', 'timeout', 'sandbox_denied', 'limit_skipped', 'interrupted', 'unconfirmed'));
