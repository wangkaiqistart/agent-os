package com.agentos.core.turn;

import java.util.Objects;

/** 同步和流式入口共用的过程事件端口。 */
public interface TurnSink {
    default boolean streaming() {
        return false;
    }

    default void onDelta(String text) {}

    default void onToolCall(String toolCallId, String name, String arguments) {}

    default void onToolResult(String toolCallId, String name, String result) {}

    default void onComplete(TurnResult result) {}

    default void onError(TurnResult result) {}

    TurnSink NOOP = new TurnSink() {};

    static TurnSink require(TurnSink sink) {
        return Objects.requireNonNull(sink, "sink");
    }
}
