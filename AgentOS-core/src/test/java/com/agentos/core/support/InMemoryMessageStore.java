package com.agentos.core.support;

import com.agentos.core.session.HistoryTurn;
import com.agentos.core.session.MessageStore;
import com.agentos.core.session.SessionSnapshot;
import com.agentos.core.session.TurnSnapshot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.Message;

public final class InMemoryMessageStore implements MessageStore {
    private final Map<java.util.UUID, List<Message>> values = new LinkedHashMap<>();

    @Override
    public synchronized void append(TurnSnapshot turn, int iteration, List<Message> messages) {
        values.computeIfAbsent(turn.turnId(), k -> new ArrayList<>()).addAll(messages);
    }

    @Override
    public synchronized List<HistoryTurn> recentTurns(SessionSnapshot session, int maxTurns) {
        List<HistoryTurn> all =
                values.entrySet().stream()
                        .map(e -> new HistoryTurn(e.getKey(), "SUCCEEDED", null, e.getValue()))
                        .toList();
        return all.size() <= maxTurns ? all : all.subList(all.size() - maxTurns, all.size());
    }
}
