package com.agentos.core.support;

import com.agentos.core.turn.TurnEvent;
import com.agentos.core.turn.TurnResult;
import com.agentos.core.turn.TurnSink;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** 记录全部事件，同时保留兼容层回调的记录，供既有测试继续使用。 */
public final class RecordingTurnSink implements TurnSink {
    public final List<String> deltas = new ArrayList<>();
    public final List<TurnEvent> events = new CopyOnWriteArrayList<>();
    public TurnResult completed;
    public TurnResult errored;
    private final boolean streaming;

    public RecordingTurnSink() {
        this(true);
    }

    public RecordingTurnSink(boolean streaming) {
        this.streaming = streaming;
    }

    @Override
    public boolean streaming() {
        return streaming;
    }

    @Override
    public void onEvent(TurnEvent event) {
        events.add(event);
        TurnSink.super.onEvent(event);
    }

    @Override
    public void onDelta(String text) {
        deltas.add(text);
    }

    @Override
    public void onComplete(TurnResult result) {
        completed = result;
    }

    @Override
    public void onError(TurnResult result) {
        errored = result;
    }

    /** 事件类型序列，便于断言顺序。 */
    public List<String> types() {
        return events.stream().map(TurnEvent::type).toList();
    }

    /** 完整序列：首条轮次开始、末条唯一终态。 */
    public void assertWellFormed() {
        RunEventAssertions.assertWellFormed(events);
    }

    /** 只校验主体事件；直接测试 ReActLoop 时没有轮次开始与终态。 */
    public void assertBodyWellFormed() {
        RunEventAssertions.assertBodyWellFormed(events);
    }
}
