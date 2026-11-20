package com.agentos.core.turn;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 只覆写旧回调的输出端经默认 onEvent 收到等价通知，其他事件类型被忽略。 */
class TurnSinkCompatibilityTest {
    private final UUID turnId = UUID.randomUUID();
    private final List<String> calls = new ArrayList<>();
    private final List<TurnResult> results = new ArrayList<>();
    private long seq;

    private final TurnSink legacy =
            new TurnSink() {
                @Override
                public void onDelta(String text) {
                    calls.add("delta:" + text);
                }

                @Override
                public void onToolCall(String id, String name, String arguments) {
                    calls.add("call:" + id + ":" + name + ":" + arguments);
                }

                @Override
                public void onToolResult(String id, String name, String result) {
                    calls.add("result:" + id + ":" + name + ":" + result);
                }

                @Override
                public void onComplete(TurnResult result) {
                    calls.add("complete");
                    results.add(result);
                }

                @Override
                public void onError(TurnResult result) {
                    calls.add("error");
                    results.add(result);
                }
            };

    private void send(TurnEventPayload payload) {
        legacy.onEvent(new TurnEvent(1, UUID.randomUUID(), turnId, ++seq, Instant.now(), payload));
    }

    @Test
    void translatesEventsToLegacyCallbacks() {
        send(new TurnEventPayload.RunStarted("a", "cli"));
        send(new TurnEventPayload.StepStarted(1));
        send(new TurnEventPayload.MessageStarted("m1", 1, null));
        send(new TurnEventPayload.MessageDelta("m1", "你好"));
        send(new TurnEventPayload.MessageEnded("m1"));
        send(new TurnEventPayload.ToolStarted("c1", "weather", "builtin", 1, "{}"));
        send(
                new TurnEventPayload.ToolFinished(
                        "c1",
                        "weather",
                        TurnEventPayload.ToolStatus.SUCCESS,
                        5,
                        false,
                        "sunny",
                        true));
        send(
                new TurnEventPayload.ToolFinished(
                        "c2",
                        "weather",
                        TurnEventPayload.ToolStatus.SKIPPED,
                        0,
                        false,
                        null,
                        false));
        send(new TurnEventPayload.RunCompleted("答", 2, PersistenceStatus.CONFIRMED));
        assertThat(calls)
                .containsExactly(
                        "delta:你好",
                        "call:c1:weather:{}",
                        "result:c1:weather:sunny",
                        "result:c2:weather:",
                        "complete");
        assertThat(results.getFirst())
                .isEqualTo(
                        new TurnResult(
                                turnId, TurnStatus.SUCCEEDED, "答", 2, PersistenceStatus.CONFIRMED));
    }

    @Test
    void failedAndCancelledBecomeOnError() {
        send(
                new TurnEventPayload.RunFailed(
                        TurnFailureCode.TIMED_OUT,
                        TurnStatus.TIMED_OUT,
                        "本轮处理超时",
                        1,
                        PersistenceStatus.CONFIRMED,
                        false));
        send(new TurnEventPayload.RunCancelled("本轮已取消", 1, PersistenceStatus.CONFIRMED, false));
        assertThat(calls).containsExactly("error", "error");
        assertThat(results.get(0).status()).isEqualTo(TurnStatus.TIMED_OUT);
        assertThat(results.get(0).failureCode()).isEqualTo(TurnFailureCode.TIMED_OUT);
        assertThat(results.get(1).status()).isEqualTo(TurnStatus.CANCELLED);
        assertThat(results.get(1).text()).isEqualTo("本轮已取消");
    }
}
