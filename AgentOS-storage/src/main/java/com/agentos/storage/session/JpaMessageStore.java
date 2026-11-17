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
    private SessionRepository sessions;
    private org.springframework.jdbc.core.JdbcTemplate jdbc;
    private com.agentos.storage.audit.AuditTransactions transactions;
    private com.agentos.core.turn.TurnBudgetRegistry budgets;
    private com.agentos.core.config.TurnProperties properties;

    public JpaMessageStore(
            SessionMessageRepository repository,
            AgentTurnRepository turns,
            SessionRepository sessions,
            org.springframework.jdbc.core.JdbcTemplate jdbc,
            com.agentos.storage.audit.AuditTransactions transactions,
            com.agentos.core.turn.TurnBudgetRegistry budgets,
            com.agentos.core.config.TurnProperties properties) {
        this(repository, turns);
        this.sessions = sessions;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.budgets = budgets;
        this.properties = properties;
    }

    public JpaMessageStore(SessionMessageRepository repository) {
        this(repository, null);
    }

    public JpaMessageStore(SessionMessageRepository repository, AgentTurnRepository turns) {
        this.repository = repository;
        this.turns = turns;
    }

    @Override
    public void append(TurnSnapshot turn, int iteration, List<Message> messages) {
        if (transactions == null) {
            appendMessages(turn, iteration, messages, Instant.now());
            return;
        }
        var budget = budgets.get(turn.turnId());
        long remaining = properties.storageTimeout().toNanos();
        if (budget != null) {
            remaining = Math.min(remaining, budget.remainingNanos());
        }
        if (remaining <= 0) {
            throw new com.agentos.core.turn.TurnDeadlineExceededException();
        }
        long deadline = System.nanoTime() + remaining;
        var task =
                new java.util.concurrent.FutureTask<Void>(() ->
                        transactions.execute(
                                java.time.Duration.ofNanos(
                                        Math.max(1, deadline - System.nanoTime())),
                                () -> {
                                    var session =
                                            sessions.findByIdForUpdate(turn.sessionId())
                                                    .orElse(null);
                                    var parent = turns.findById(turn.turnId()).orElse(null);
                                    Instant now =
                                            jdbc.queryForObject(
                                                    "select clock_timestamp()",
                                                    (rs, row) -> rs.getTimestamp(1).toInstant());
                                    if ((budget != null && budget.expired())
                                            || (parent != null
                                                    && !parent.getDeadlineAt().isAfter(now))) {
                                        throw new com.agentos.core.turn
                                                .TurnDeadlineExceededException();
                                    }
                                    if (budget != null && budget.cancelled()) {
                                        throw new com.agentos.core.turn.LeaseLostException();
                                    }
                                    if (System.nanoTime() >= deadline
                                            || session == null
                                            || parent == null
                                            || !parent.getSessionId().equals(turn.sessionId())
                                            || !"active".equals(session.getStatus())
                                            || !"RUNNING".equals(parent.getStatus())
                                            || !turn.owner().equals(session.getLeaseOwner())
                                            || session.getLeaseUntil() == null
                                            || !session.getLeaseUntil().isAfter(now)) {
                                        throw new com.agentos.core.turn.LeaseLostException();
                                    }
                                    appendMessages(turn, iteration, messages, now);
                                    return null;
                                }));
        Thread.ofVirtual().name("message-storage").start(task);
        try {
            task.get(
                    Math.max(1, deadline - System.nanoTime()),
                    java.util.concurrent.TimeUnit.NANOSECONDS);
        } catch (java.util.concurrent.ExecutionException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("消息追加失败");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new com.agentos.core.turn.LeaseLostException();
        } catch (java.util.concurrent.TimeoutException failure) {
            throw new IllegalStateException("消息追加超时，提交未确认");
        } finally {
            task.cancel(true);
        }
    }

    private void appendMessages(
            TurnSnapshot turn, int iteration, List<Message> messages, Instant now) {
        // 一批消息在一个短事务中追加；工具调用和响应分别写 JSONB，不能只靠文本猜调用 ID。
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
