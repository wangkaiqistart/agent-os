package com.agentos.storage.session;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data 根据接口生成实现；UUID 是 sessions 主键类型，锁查询必须在调用方事务内执行。 */
public interface SessionRepository extends JpaRepository<SessionEntity, UUID> {
    /** 派生查询：方法名中的字段和 And 条件被解析为 SQL，无需手写实现类。 */
    Optional<SessionEntity> findByChannelAndUserIdAndProfileNameAndStatus(
            String channel, String userId, String profileName, String status);

    /** 原生 PostgreSQL 行锁查询；锁保持到外层事务结束，主要用于续租。 */
    @Query(
            value = "SELECT * FROM sessions WHERE session_id = :sessionId FOR UPDATE",
            nativeQuery = true)
    Optional<SessionEntity> findByIdForUpdate(@Param("sessionId") UUID sessionId);

    /**
     * 利用 active 会话的部分唯一索引解决“同时创建同一会话”的竞争。
     *
     * <p>冲突时不覆盖已有行，返回受影响行数（插入为 1，冲突为 0）；调用方随后锁定该活跃行。
     * Modifying 表示这是写 SQL，不是把 SELECT 结果映射为实体。
     */
    @Modifying
    @Query(
            value =
                    """
                    INSERT INTO sessions(session_id, profile_name, channel, user_id, status, created_at, last_active_at)
                    VALUES (:id, :profile, :channel, :user, 'active', :at, :at)
                    ON CONFLICT (channel, user_id, profile_name) WHERE status = 'active' DO NOTHING
                    """,
            nativeQuery = true)
    int insertActiveIfAbsent(
            @Param("id") UUID id,
            @Param("profile") String profile,
            @Param("channel") String channel,
            @Param("user") String user,
            @Param("at") java.time.Instant at);

    /** 按会话自然键取活跃行并锁住，供 startTurn 在锁内判断租约。 */
    @Query(
            value =
                    "SELECT * FROM sessions WHERE channel = :channel AND user_id = :user AND profile_name = :profile AND status = 'active' FOR UPDATE",
            nativeQuery = true)
    Optional<SessionEntity> findActiveForUpdate(
            @Param("channel") String channel,
            @Param("user") String user,
            @Param("profile") String profile);
}
