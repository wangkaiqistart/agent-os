package com.agentos.core.turn;

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

    public ToolExecutor(ToolCallingManager manager) {
        this.manager = Objects.requireNonNull(manager);
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
        List<Message> complete = List.copyOf(result.conversationHistory());
        List<Message> added = List.copyOf(complete.subList(history.size(), complete.size()));
        return new Result(complete, added, result.returnDirect());
    }

    public record Result(List<Message> history, List<Message> added, boolean returnDirect) {}
}
