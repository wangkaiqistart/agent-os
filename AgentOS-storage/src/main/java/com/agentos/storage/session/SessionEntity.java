package com.agentos.storage.session;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * sessions 表映射：一个会话包含多次用户轮次，并保存当前执行者的限时租约。
 *
 * <p>注解放在字段上，JPA 直接读写字段；getter 只供业务读取。这里不维护实体关联和级联，
 * 其他表通过 UUID 关联，写入顺序与事务边界由存储服务控制。表结构由 Flyway 创建。
 */
@Entity
@Table(name = "sessions")
public class SessionEntity {
    @Id
    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    // channel、userId、profileName 共同标识一个活跃会话；归档后允许创建同自然键的新会话。
    @Column(name = "profile_name", nullable = false)
    private String profileName;

    @Column(nullable = false)
    private String channel;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(nullable = false)
    private String status = "active";

    // owner 当前为 turnId 字符串；until 是可接管期限，并非模型业务截止时间。
    @Column(name = "lease_owner")
    private String leaseOwner;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_active_at", nullable = false)
    private Instant lastActiveAt;

    @Column(name = "archived_at")
    private Instant archivedAt;

    /** JPA 从数据库恢复实体时需要无参构造器，业务代码使用下面的完整构造器。 */
    protected SessionEntity() {}

    public SessionEntity(
            UUID sessionId, String profileName, String channel, String userId, Instant now) {
        this.sessionId = sessionId;
        this.profileName = profileName;
        this.channel = channel;
        this.userId = userId;
        this.createdAt = now;
        this.lastActiveAt = now;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public String getProfileName() {
        return profileName;
    }

    public String getChannel() {
        return channel;
    }

    public String getUserId() {
        return userId;
    }

    public String getStatus() {
        return status;
    }

    public String getLeaseOwner() {
        return leaseOwner;
    }

    public Instant getLeaseUntil() {
        return leaseUntil;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastActiveAt() {
        return lastActiveAt;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    /** 只修改实体字段，检查归属、加锁和事务提交由 JpaTurnStore 负责。 */
    public void lease(String owner, Instant until) {
        leaseOwner = owner;
        leaseUntil = until;
    }

    /** 归档同时清租约；调用方必须先判断是否空闲。 */
    public void archive(Instant at) {
        status = "archived";
        archivedAt = at;
        leaseOwner = null;
        leaseUntil = null;
    }

    /** 更新最后活跃时间，保留原始创建时间。 */
    public void touch(Instant at) {
        lastActiveAt = at;
    }
}
