package com.agentos.core.support;

import com.agentos.core.turn.TurnResult;
import com.agentos.core.turn.TurnSink;
import java.util.ArrayList;
import java.util.List;

public final class RecordingTurnSink implements TurnSink {
    public final List<String> deltas = new ArrayList<>();
    public TurnResult completed;
    public TurnResult errored;

    @Override
    public boolean streaming() {
        return true;
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
}
