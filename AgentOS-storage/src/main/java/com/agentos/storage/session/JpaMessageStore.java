package com.agentos.storage.session;

import com.agentos.core.session.HistoryTurn;
import com.agentos.core.session.MessageStore;
import com.agentos.core.session.SessionSnapshot;
import com.agentos.core.session.TurnSnapshot;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.transaction.annotation.Transactional;

public class JpaMessageStore implements MessageStore {
    private final SessionMessageRepository repository;

    public JpaMessageStore(SessionMessageRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public void append(TurnSnapshot turn, int iteration, List<Message> messages) {
        Instant now = Instant.now();
        for (Message message : messages) {
            String type =
                    message instanceof UserMessage
                            ? "USER"
                            : message instanceof AssistantMessage ? "ASSISTANT" : "TOOL";
            repository.save(
                    new SessionMessageEntity(
                            turn.sessionId(),
                            turn.turnId(),
                            iteration,
                            type,
                            message.getText(),
                            null,
                            null,
                            java.util.Map.of(),
                            now));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<HistoryTurn> recentTurns(SessionSnapshot session, int maxTurns) {
        if (maxTurns <= 0) {
            return List.of();
        }
        List<SessionMessageEntity> rows =
                repository.findBySessionIdOrderByIdAsc(session.sessionId());
        List<HistoryTurn> result = new ArrayList<>();
        for (SessionMessageEntity row : rows) {
            if (result.isEmpty()
                    || !result.get(result.size() - 1).turnId().equals(row.getTurnId())) {
                result.add(new HistoryTurn(row.getTurnId(), "SUCCEEDED", null, List.of()));
            }
            HistoryTurn previous = result.get(result.size() - 1);
            List<Message> existing = new ArrayList<>(previous.messages());
            existing.add(toMessage(row));
            result.set(result.size() - 1, previous.withMessages(existing));
        }
        return result.size() <= maxTurns
                ? result
                : result.subList(result.size() - maxTurns, result.size());
    }

    private static Message toMessage(SessionMessageEntity row) {
        String content = row.getContent() == null ? "" : row.getContent();
        return switch (row.getType()) {
            case "ASSISTANT" -> new AssistantMessage(content);
            default -> new UserMessage(content);
        };
    }
}
