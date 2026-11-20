package com.agentos.core.turn;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 一轮内发生的一件事，由发布器分配序号后交给 {@link TurnSink#onEvent(TurnEvent)}。
 *
 * @param schemaVersion 契约版本，当前固定为 1；新增类型或可选字段不升版本
 * @param sessionId 会话标识；只有起步未确认时的开始与失败事件允许为 null
 * @param turnId 轮次标识
 * @param seq 本轮内序号，从 1 开始逐条加 1
 * @param createdAt 发布器分配序号的时间
 * @param payload 类型相关负载
 */
public record TurnEvent(
        int schemaVersion,
        UUID sessionId,
        UUID turnId,
        long seq,
        Instant createdAt,
        TurnEventPayload payload) {

    public static final int SCHEMA_VERSION = 1;

    public TurnEvent {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("不支持的事件契约版本：" + schemaVersion);
        }
        Objects.requireNonNull(turnId, "turnId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(payload, "payload");
        if (seq < 1) {
            throw new IllegalArgumentException("seq 必须从 1 开始");
        }
        if (sessionId == null && !startUnconfirmed(payload)) {
            throw new IllegalArgumentException("只有起步未确认的事件允许缺少会话标识");
        }
    }

    /** 契约里的事件类型名，由负载派生。 */
    public String type() {
        return payload.type();
    }

    private static boolean startUnconfirmed(TurnEventPayload payload) {
        return payload instanceof TurnEventPayload.RunStarted
                || (payload instanceof TurnEventPayload.RunFailed failed
                        && failed.code() == TurnFailureCode.START_UNCONFIRMED);
    }
}
