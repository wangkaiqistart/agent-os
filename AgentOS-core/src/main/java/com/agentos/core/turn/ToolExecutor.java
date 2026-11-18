package com.agentos.core.turn;

import com.agentos.core.audit.ToolInvocationRecord;
import com.agentos.core.audit.ToolInvocationRecorder;
import com.agentos.core.tool.Redactor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;

/**
 * 唯一工具执行入口，始终委托容器注入的官方 Manager，不自行遍历执行 callback。
 * Prompt 包含本轮累计历史和原治理回调；只在整批正常返回后暴露完整历史及新增消息。
 */
public final class ToolExecutor {
    private final ToolCallingManager manager;
    private final ToolInvocationRecorder recorder;
    private final Redactor redactor;

    public ToolExecutor(ToolCallingManager manager) {
        this(
                manager,
                new ToolInvocationRecorder() {
                    public ToolInvocationRecord begin(ToolInvocationRecord record) {
                        return record;
                    }

                    public void complete(ToolInvocationRecord record) {}

                    public void skipped(ToolInvocationRecord record) {}
                },
                new Redactor(List::of));
    }

    public ToolExecutor(
            ToolCallingManager manager, ToolInvocationRecorder recorder, Redactor redactor) {
        this.manager = Objects.requireNonNull(manager);
        this.recorder = Objects.requireNonNull(recorder);
        this.redactor = Objects.requireNonNull(redactor);
    }

    public Result execute(
            List<Message> history,
            ChatResponse response,
            List<ToolCallback> callbacks,
            TurnContext context,
            TurnExecutionBudget budget,
            Runnable checkpoint) {
        var generation = Objects.requireNonNull(response.getResult(), "模型响应缺少候选结果");
        var ledger = new ToolCallLedger(generation.getOutput().getToolCalls(), checkpoint);
        ledger.checkpoint();
        if (!ledger.validArguments()) {
            return rejectBatch(history, generation.getOutput(), ledger, context);
        }
        var options =
                ToolCallingChatOptions.builder()
                        .toolCallbacks(callbacks)
                        .toolContext(
                                Map.of(
                                        ToolContextKeys.TURN_CONTEXT,
                                        context,
                                        ToolContextKeys.TOOL_CALL_LEDGER,
                                        ledger,
                                        ToolContextKeys.TURN_BUDGET,
                                        budget))
                        .build();
        var result = manager.executeToolCalls(new Prompt(history, options), response);
        for (var call : ledger.pending()) {
            ledger.checkpoint();
            recorder.skipped(
                    new ToolInvocationRecord(
                            context.sessionId(),
                            context.turnId(),
                            context.iteration(),
                            call.id(),
                            call.name(),
                            "builtin",
                            redactor.summarize(call.arguments(), 10000),
                            null,
                            null,
                            false,
                            "limit_skipped",
                            "工具调用次数达到上限",
                            true,
                            0,
                            0L));
        }
        // 原始参数只用于本次执行；后续模型请求与消息落库共用脱敏后的完整历史。
        List<Message> complete =
                result.conversationHistory().stream().map(this::safeHistory).toList();
        List<Message> added = List.copyOf(complete.subList(history.size(), complete.size()));
        return new Result(complete, added, result.returnDirect());
    }

    private Result rejectBatch(
            List<Message> history,
            AssistantMessage assistant,
            ToolCallLedger ledger,
            TurnContext context) {
        String error = "本批工具参数不是合法 JSON 对象，所有调用均未执行";
        var responses = new ArrayList<ToolResponseMessage.ToolResponse>();
        for (var call : assistant.getToolCalls()) {
            ledger.checkpoint();
            recorder.skipped(
                    new ToolInvocationRecord(
                            context.sessionId(),
                            context.turnId(),
                            context.iteration(),
                            call.id(),
                            call.name(),
                            "builtin",
                            redactor.summarize(call.arguments(), 10000),
                            null,
                            null,
                            false,
                            "failed",
                            error,
                            false,
                            0,
                            0L));
            responses.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(), error));
        }
        var complete = new ArrayList<Message>(history);
        complete.add(assistant);
        complete.add(ToolResponseMessage.builder().responses(responses).build());
        List<Message> safe = complete.stream().map(this::safeHistory).toList();
        // 安全拒绝只构建消息，不执行任何 callback；模型可在本轮继续修正参数。
        return new Result(safe, List.copyOf(safe.subList(history.size(), safe.size())), false);
    }

    private Message safeHistory(Message message) {
        if (!(message instanceof AssistantMessage assistant)
                || assistant.getToolCalls().isEmpty()) {
            return message;
        }
        return AssistantMessage.builder()
                .content(assistant.getText())
                .properties(assistant.getMetadata())
                .media(assistant.getMedia())
                .toolCalls(
                        assistant.getToolCalls().stream()
                                .map(call ->
                                        new AssistantMessage.ToolCall(
                                                call.id(),
                                                call.type(),
                                                call.name(),
                                                redactor.redact(call.arguments())))
                                .toList())
                .build();
    }

    public record Result(List<Message> history, List<Message> added, boolean returnDirect) {}
}
