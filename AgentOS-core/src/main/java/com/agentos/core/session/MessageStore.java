package com.agentos.core.session;

import java.util.List;
import org.springframework.ai.chat.messages.Message;

/** 追加消息和读取历史的端口。 */
public interface MessageStore {
    void append(TurnSnapshot turn, int iteration, List<Message> messages);

    List<HistoryTurn> recentTurns(SessionSnapshot session, int maxTurns);
}
