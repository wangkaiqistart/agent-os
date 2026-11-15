package com.agentos.core.support;

import com.agentos.core.session.HistoryTurn;
import com.agentos.core.session.MessageStore;
import com.agentos.core.session.SessionSnapshot;
import com.agentos.core.session.TurnSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.ai.chat.messages.Message;

public final class InMemoryMessageStore implements MessageStore {
    private final Map<java.util.UUID, List<Message>> values = new ConcurrentHashMap<>();

    public void append(TurnSnapshot turn, int iteration, List<Message> messages) {
        values.computeIfAbsent(turn.turnId(), k -> new ArrayList<>()).addAll(messages);
    }

    public List<HistoryTurn> recentTurns(SessionSnapshot session, int maxTurns) {
        return values.entrySet().stream()
                .map(e -> new HistoryTurn(e.getKey(), "SUCCEEDED", null, e.getValue()))
                .limit(maxTurns)
                .toList();
    }
}
