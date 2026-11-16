package com.agentos.storage.session;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 工具审计仓库；由记录器按模型调用 ID 定位同行，不依赖参数或结果文本猜测。 */
public interface ToolInvocationRepository extends JpaRepository<ToolInvocationEntity, Long> {
    /** 同一轮、同一迭代、同一 toolCallId 定位一次逻辑调用，与迁移中的唯一约束一致。 */
    Optional<ToolInvocationEntity> findByTurnIdAndIterationAndToolCallId(
            UUID turnId, int iteration, String toolCallId);

    @org.springframework.data.jpa.repository.Lock(
            jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query(
            "select t from ToolInvocationEntity t where t.turnId=:turnId and t.iteration=:iteration and t.toolCallId=:toolCallId")
    Optional<ToolInvocationEntity> findForUpdate(UUID turnId, int iteration, String toolCallId);

    /** 为查询未完成调用预留；工具状态使用 in_progress 等小写值，与轮次状态区分。 */
    List<ToolInvocationEntity> findByTurnIdAndStatus(UUID turnId, String status);
}
