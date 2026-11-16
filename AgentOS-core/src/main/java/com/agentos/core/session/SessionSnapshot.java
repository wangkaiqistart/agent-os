package com.agentos.core.session;

import java.time.Instant;
import java.util.UUID;

/**
 * 会话查询的值快照，不持有 JPA EntityManager，也不会随数据库变化自动更新。
 *
 * @param sessionId 数据库会话 UUID
 * @param key 渠道、用户和 Agent 组成的自然键
 * @param status 会话状态，例如 active、archived
 * @param leaseOwner 当前租约持有者，空闲时为 null
 * @param leaseUntil 租约有效截止时间，需配合数据库时钟判断
 */
public record SessionSnapshot(
        UUID sessionId, SessionKey key, String status, String leaseOwner, Instant leaseUntil) {}
