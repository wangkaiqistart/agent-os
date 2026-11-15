package com.agentos.core.session;

import java.util.List;
import java.util.UUID;
import org.springframework.ai.chat.messages.Message;

/** 一整个历史轮次及其消息。 */
public record HistoryTurn(UUID turnId, String status, String errorMessage, List<Message> messages) {
    public HistoryTurn {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }

    public HistoryTurn withMessages(List<Message> replacement) {
        return new HistoryTurn(turnId, status, errorMessage, replacement);
    }
}
