package com.agentos.core.turn;

import com.agentos.core.audit.ToolInvocationRecord;
import com.agentos.core.audit.ToolInvocationRecorder;
import com.agentos.core.tool.Redactor;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.ai.chat.messages.Message;
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
                            redactor.redact(call.arguments()),
                            null,
                            null,
                            false,
                            "limit_skipped",
                            "工具调用次数达到上限",
                            true,
                            0,
                            0L));
        }
        List<Message> complete = List.copyOf(result.conversationHistory());
        List<Message> added = List.copyOf(complete.subList(history.size(), complete.size()));
        return new Result(complete, added, result.returnDirect());
    }

    public record Result(List<Message> history, List<Message> added, boolean returnDirect) {}
}
