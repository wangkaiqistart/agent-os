package com.agentos.storage.session;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 工具审计表的存储基础；当前无工具执行主链路调用它，不代表工具能力已接通。 */
public interface ToolInvocationRepository extends JpaRepository<ToolInvocationEntity, Long> {
    /** 同一轮、同一迭代、同一 toolCallId 定位一次逻辑调用，与迁移中的唯一约束一致。 */
    Optional<ToolInvocationEntity> findByTurnIdAndIterationAndToolCallId(
            UUID turnId, int iteration, String toolCallId);

    /** 为查询未完成调用预留；工具状态使用 in_progress 等小写值，与轮次状态区分。 */
    List<ToolInvocationEntity> findByTurnIdAndStatus(UUID turnId, String status);
}
