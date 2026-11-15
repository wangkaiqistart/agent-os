package com.agentos.core.session;

import java.time.Instant;
import java.util.UUID;

/** 轮次持久化快照。 */
public record TurnSnapshot(
        UUID turnId,
        UUID sessionId,
        SessionKey sessionKey,
        String owner,
        Instant deadline,
        String status) {}
