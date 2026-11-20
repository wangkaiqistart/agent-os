-- 007：用户主动取消的轮次以 CANCELLED 结束，与进程关闭、租约丢失导致的 INTERRUPTED 区分。
-- 只重建状态 CHECK 约束，不改已发布迁移、不动历史数据。
ALTER TABLE agent_turns DROP CONSTRAINT agent_turns_status_check;

ALTER TABLE agent_turns ADD CONSTRAINT agent_turns_status_check
    CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'TIMED_OUT', 'MAX_ITERATIONS', 'INTERRUPTED', 'LIMIT_EXCEEDED', 'CANCELLED'));
