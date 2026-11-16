package com.agentos.storage.session;

import com.agentos.core.session.HistoryTurn;
import com.agentos.core.session.MessageStore;
import com.agentos.core.session.SessionSnapshot;
import com.agentos.core.session.TurnSnapshot;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.transaction.annotation.Transactional;

/**
 * 消息账本的 JPA 实现，保存用户、助手和本次迭代完整的工具结构。
 *
 * <p>读取时先按 agent_turns 选择轮次，再按消息 id 还原完整消息组；RUNNING 轮次不会进入下一轮。
 */
public class JpaMessageStore implements MessageStore {
    private final SessionMessageRepository repository;
    private final AgentTurnRepository turns;

    public JpaMessageStore(SessionMessageRepository repository) {
        this(repository, null);
    }

    public JpaMessageStore(SessionMessageRepository repository, AgentTurnRepository turns) {
        this.repository = repository;
        this.turns = turns;
    }

    @Override
    @Transactional
    public void append(TurnSnapshot turn, int iteration, List<Message> messages) {
        // 一批消息在一个短事务中追加；工具调用和响应分别写 JSONB，不能只靠文本猜调用 ID。
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
                            message instanceof AssistantMessage assistant
                                    ? assistant.getToolCalls()
                                    : null,
                            message instanceof ToolResponseMessage tool
                                    ? tool.getResponses()
                                    : null,
                            message.getMetadata(),
                            now));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<HistoryTurn> recentTurns(SessionSnapshot session, int maxTurns) {
        if (maxTurns <= 0) {
            return List.of();
        }
        Map<java.util.UUID, AgentTurnEntity> selected =
                selectedTurns(session.sessionId(), maxTurns);
        List<SessionMessageEntity> rows =
                repository.findBySessionIdOrderByIdAsc(session.sessionId());
        // 轮次先选、消息后读；不能对消息行直接 LIMIT，否则会拆开工具请求和工具响应。
        List<HistoryTurn> result = new ArrayList<>();
        for (SessionMessageEntity row : rows) {
            AgentTurnEntity turn = selected == null ? null : selected.get(row.getTurnId());
            if (selected != null && (turn == null || "RUNNING".equals(turn.getStatus()))) {
                continue;
            }
            if (result.isEmpty()
                    || !result.get(result.size() - 1).turnId().equals(row.getTurnId())) {
                result.add(
                        new HistoryTurn(
                                row.getTurnId(),
                                turn == null ? "SUCCEEDED" : turn.getStatus(),
                                turn == null ? null : turn.getErrorMessage(),
                                List.of()));
            }
            HistoryTurn previous = result.get(result.size() - 1);
            List<Message> existing = new ArrayList<>(previous.messages());
            existing.add(toMessage(row));
            result.set(result.size() - 1, previous.withMessages(existing));
        }
        return List.copyOf(result);
    }

    private static Message toMessage(SessionMessageEntity row) {
        // 从 JSONB 结构恢复工具调用和工具响应，不能只用 content 猜调用 ID。
        String content = row.getContent() == null ? "" : row.getContent();
        return switch (row.getType()) {
            case "ASSISTANT" ->
                    AssistantMessage.builder()
                            .content(content)
                            .toolCalls(toolCalls(row.getToolCalls()))
                            .properties(row.getMetadata() == null ? Map.of() : row.getMetadata())
                            .build();
            case "TOOL" ->
                    ToolResponseMessage.builder()
                            .responses(toolResponses(row.getToolResponses()))
                            .metadata(row.getMetadata() == null ? Map.of() : row.getMetadata())
                            .build();
            default ->
                    UserMessage.builder()
                            .text(content)
                            .metadata(row.getMetadata() == null ? Map.of() : row.getMetadata())
                            .build();
        };
    }

    private Map<java.util.UUID, AgentTurnEntity> selectedTurns(
            java.util.UUID sessionId, int maxTurns) {
        if (turns == null) {
            // 兼容只注入消息 Repository 的轻量测试；正式装配总是提供轮次 Repository。
            return null;
        }
        // 先排除 RUNNING，再取最近 maxTurns；否则当前轮次会占掉一个名额，少恢复一轮已完成历史。
        List<AgentTurnEntity> all =
                turns.findBySessionIdAndStatusNotOrderByStartedAtAsc(sessionId, "RUNNING");
        int from = Math.max(0, all.size() - maxTurns);
        Map<java.util.UUID, AgentTurnEntity> result = new HashMap<>();
        for (AgentTurnEntity turn : all.subList(from, all.size())) {
            result.put(turn.getTurnId(), turn);
        }
        return result;
    }

    private static List<AssistantMessage.ToolCall> toolCalls(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<AssistantMessage.ToolCall> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof AssistantMessage.ToolCall call) {
                result.add(call);
            } else if (item instanceof Map<?, ?> map) {
                result.add(
                        new AssistantMessage.ToolCall(
                                text(map.get("id")),
                                text(map.get("type")),
                                text(map.get("name")),
                                text(map.get("arguments"))));
            }
        }
        return List.copyOf(result);
    }

    private static List<ToolResponseMessage.ToolResponse> toolResponses(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<ToolResponseMessage.ToolResponse> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof ToolResponseMessage.ToolResponse response) {
                result.add(response);
            } else if (item instanceof Map<?, ?> map) {
                result.add(
                        new ToolResponseMessage.ToolResponse(
                                text(map.get("id")),
                                text(map.get("name")),
                                text(map.get("responseData"))));
            }
        }
        return List.copyOf(result);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
