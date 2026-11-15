package com.agentos.core.turn;

public interface AgentService {
    TurnResult process(ProcessRequest request);

    TurnResult process(ProcessRequest request, TurnSink sink);
}
