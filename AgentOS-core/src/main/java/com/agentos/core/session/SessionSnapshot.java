package com.agentos.core.session;

import java.time.Instant;
import java.util.UUID;

/** 会话查询快照，不暴露 JPA 实体。 */
public record SessionSnapshot(
        UUID sessionId, SessionKey key, String status, String leaseOwner, Instant leaseUntil) {}
