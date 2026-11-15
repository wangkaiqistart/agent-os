package com.agentos.core.turn;

import com.agentos.core.agent.LoadedAgent;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record TurnContext(
        UUID sessionId,
        UUID turnId,
        LoadedAgent agent,
        String trigger,
        int iteration,
        Instant deadline) {
    public TurnContext {
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
        turnId = Objects.requireNonNull(turnId, "turnId");
        agent = Objects.requireNonNull(agent, "agent");
        if (iteration < 0) {
            throw new IllegalArgumentException("iteration 不能为负数");
        }
        deadline = Objects.requireNonNull(deadline, "deadline");
    }

    public TurnContext forIteration(int nextIteration) {
        return new TurnContext(sessionId, turnId, agent, trigger, nextIteration, deadline);
    }
}
