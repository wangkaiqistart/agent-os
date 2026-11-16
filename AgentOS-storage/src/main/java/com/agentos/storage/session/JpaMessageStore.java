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

/**
 * 消息账本的最小 JPA 实现，当前用于 US1 的纯文本用户/助手消息。
 *
 * <p>工具调用与工具响应字段尚未序列化，历史的真实轮次状态也尚未恢复；
 * 不能据此认为完整的多轮工具上下文已经可用。当前 ReActLoop 尚未读取这里的历史。
 */
public class JpaMessageStore implements MessageStore {
    private final SessionMessageRepository repository;

    public JpaMessageStore(SessionMessageRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public void append(TurnSnapshot turn, int iteration, List<Message> messages) {
        // 一批消息在一个短事务中追加。这里只保存角色和文本，工具结构为空、metadata 为空表。
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
        // 当前先读全部消息，再按相邻 turnId 分组并取最后 N 轮；没有 SQL 分页或 token 截断。
        List<HistoryTurn> result = new ArrayList<>();
        for (SessionMessageEntity row : rows) {
            if (result.isEmpty()
                    || !result.get(result.size() - 1).turnId().equals(row.getTurnId())) {
                // 暂用固定状态，未关联 agent_turns 恢复失败或中断原因。
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
        // 目前只准确恢复 ASSISTANT/USER；TOOL 仍落入 default，不能用于完整工具历史重放。
        String content = row.getContent() == null ? "" : row.getContent();
        return switch (row.getType()) {
            case "ASSISTANT" -> new AssistantMessage(content);
            default -> new UserMessage(content);
        };
    }
}
