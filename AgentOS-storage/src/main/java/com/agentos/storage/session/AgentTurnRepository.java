package com.agentos.storage.session;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** agent_turns 的 CRUD 代理；轮次主键由应用生成，不使用消息表的自增序号。 */
public interface AgentTurnRepository extends JpaRepository<AgentTurnEntity, UUID> {
    /** 历史读取接口；回收使用下方的定向查询，不能逐条更新已完成历史。 */
    List<AgentTurnEntity> findBySessionIdOrderByStartedAtAsc(UUID sessionId);

    /** 会话已加锁，只加载失效轮次及有残留工具/租约的终态轮次。 */
    @Query(
            value =
                    """
                    SELECT t.* FROM agent_turns t JOIN sessions s ON s.session_id = t.session_id
                    WHERE t.session_id = :sessionId AND (
                        (t.status = 'RUNNING' AND (
                            s.lease_owner IS DISTINCT FROM CAST(t.turn_id AS text)
                            OR s.lease_until IS NULL OR s.lease_until <= :now))
                        OR (t.status <> 'RUNNING' AND (
                            s.lease_owner = CAST(t.turn_id AS text)
                            OR EXISTS (SELECT 1 FROM tool_invocations i
                                WHERE i.turn_id = t.turn_id AND i.status = 'in_progress')))
                    ) ORDER BY t.started_at, t.turn_id
                    """,
            nativeQuery = true)
    List<AgentTurnEntity> findRecoveryCandidates(
            @Param("sessionId") UUID sessionId, @Param("now") java.time.Instant now);

    List<AgentTurnEntity> findByStatus(String status);

    /** 先排除仍在执行的轮次，再按完整轮次选择，避免 RUNNING 占掉历史配额。 */
    List<AgentTurnEntity> findBySessionIdAndStatusNotOrderByStartedAtAsc(
            UUID sessionId, String status);
}
