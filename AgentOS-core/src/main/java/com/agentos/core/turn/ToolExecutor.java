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
        return execute(
                history,
                response,
                callbacks,
                context,
                budget,
                checkpoint,
                null,
                com.agentos.core.interaction.TurnRuntimeCapabilities.NONE,
                null);
    }

    public Result execute(
            List<Message> history,
            ChatResponse response,
            com.agentos.core.tool.ToolCatalogSnapshot snapshot,
            TurnContext context,
            TurnExecutionBudget budget,
            Runnable checkpoint) {
        return execute(
                history,
                response,
                snapshot,
                context,
                budget,
                checkpoint,
                com.agentos.core.interaction.TurnRuntimeCapabilities.NONE);
    }

    public Result execute(
            List<Message> history,
            ChatResponse response,
            com.agentos.core.tool.ToolCatalogSnapshot snapshot,
            TurnContext context,
            TurnExecutionBudget budget,
            Runnable checkpoint,
            com.agentos.core.interaction.TurnRuntimeCapabilities capabilities) {
        return execute(
                history, response, snapshot, context, budget, checkpoint, capabilities, null);
    }

    /**
     * 带事件发布器的执行入口。发布器经 ToolContext 交给治理层，由它按单个工具发开始 / 结束事件；
     * 没有实际执行的调用（次数超限被跳过、参数非法整批拒绝）由这里补发一对事件。
     */
    public Result execute(
            List<Message> history,
            ChatResponse response,
            com.agentos.core.tool.ToolCatalogSnapshot snapshot,
            TurnContext context,
            TurnExecutionBudget budget,
            Runnable checkpoint,
            com.agentos.core.interaction.TurnRuntimeCapabilities capabilities,
            TurnEventPublisher events) {
        return execute(
                history,
                response,
                snapshot.executionCallbacks(),
                context,
                budget,
                checkpoint,
                snapshot,
                capabilities,
                events);
    }

    private Result execute(
            List<Message> history,
            ChatResponse response,
            List<ToolCallback> callbacks,
            TurnContext context,
            TurnExecutionBudget budget,
            Runnable checkpoint,
            com.agentos.core.tool.ToolCatalogSnapshot snapshot,
            com.agentos.core.interaction.TurnRuntimeCapabilities capabilities,
            TurnEventPublisher publisher) {
        TurnEventPublisher events = publisher == null ? NO_EVENTS : publisher;
        var generation = Objects.requireNonNull(response.getResult(), "模型响应缺少候选结果");
        var ledger = new ToolCallLedger(generation.getOutput().getToolCalls(), checkpoint);
        ledger.checkpoint();
        if (!ledger.validArguments()) {
            return rejectBatch(history, generation.getOutput(), ledger, context, snapshot, events);
        }
        var values = new java.util.HashMap<String, Object>();
        values.put(ToolContextKeys.TURN_CONTEXT, context);
        values.put(ToolContextKeys.RUNTIME_CAPABILITIES, capabilities);
        values.put(ToolContextKeys.TOOL_CALL_LEDGER, ledger);
        values.put(ToolContextKeys.TURN_BUDGET, budget);
        values.put(ToolContextKeys.TURN_EVENTS, events);
        if (snapshot != null) {
            values.put(ToolContextKeys.TOOL_SNAPSHOT, snapshot);
        }
        var options =
                ToolCallingChatOptions.builder()
                        .toolCallbacks(callbacks)
                        .toolContext(Map.copyOf(values))
                        .build();
        var result = manager.executeToolCalls(new Prompt(history, options), response);
        Map<String, String> responses = responseData(result.conversationHistory());
        for (var call : ledger.pending()) {
            ledger.checkpoint();
            String arguments = redactor.summarize(call.arguments(), 10000);
            String source = snapshot == null ? "builtin" : snapshot.sourceOf(call.name());
            events.publish(
                    new TurnEventPayload.ToolStarted(
                            call.id(), call.name(), source, context.iteration(), arguments));
            events.publish(
                    new TurnEventPayload.ToolFinished(
                            call.id(),
                            call.name(),
                            TurnEventPayload.ToolStatus.SKIPPED,
                            0,
                            false,
                            responses.containsKey(call.id())
                                    ? redactor.redact(responses.get(call.id()))
                                    : null,
                            false));
            recorder.skipped(
                    new ToolInvocationRecord(
                            context.sessionId(),
                            context.turnId(),
                            context.iteration(),
                            call.id(),
                            call.name(),
                            snapshot == null ? "builtin" : snapshot.sourceOf(call.name()),
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
            TurnContext context,
            com.agentos.core.tool.ToolCatalogSnapshot snapshot,
            TurnEventPublisher events) {
        String error = "本批工具参数不是合法 JSON 对象，所有调用均未执行";
        var responses = new ArrayList<ToolResponseMessage.ToolResponse>();
        for (var call : assistant.getToolCalls()) {
            ledger.checkpoint();
            events.publish(
                    new TurnEventPayload.ToolStarted(
                            call.id(),
                            call.name(),
                            snapshot == null ? "builtin" : snapshot.sourceOf(call.name()),
                            context.iteration(),
                            redactor.summarize(call.arguments(), 10000)));
            events.publish(
                    new TurnEventPayload.ToolFinished(
                            call.id(),
                            call.name(),
                            TurnEventPayload.ToolStatus.FAILED,
                            0,
                            false,
                            error,
                            false));
            recorder.skipped(
                    new ToolInvocationRecord(
                            context.sessionId(),
                            context.turnId(),
                            context.iteration(),
                            call.id(),
                            call.name(),
                            snapshot == null ? "builtin" : snapshot.sourceOf(call.name()),
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

    /** 没有装配发布器的调用方（旧测试、直接调用）不产生事件。 */
    private static final TurnEventPublisher NO_EVENTS = payload -> {};

    /** 按调用 ID 取 Manager 返回的工具响应正文，供跳过事件附带模型实际看到的提示。 */
    private static Map<String, String> responseData(List<Message> history) {
        var data = new java.util.HashMap<String, String>();
        for (Message message : history) {
            if (message instanceof ToolResponseMessage toolMessage) {
                for (var item : toolMessage.getResponses()) {
                    data.put(item.id(), item.responseData());
                }
            }
        }
        return data;
    }

    public record Result(List<Message> history, List<Message> added, boolean returnDirect) {}
}
