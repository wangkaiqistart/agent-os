package com.agentos.boot.support;

import com.agentos.core.tool.Redactor;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

/** 确定性模型替身；只捕获可见工具和脱敏回填，不保留问题、上下文或原始参数。 */
public final class ToolAcceptanceModel implements ChatModel {
    public record Definition(String name, String description, String schema) {}

    public record Feedback(String id, String name, String safeResult) {}

    public record Capture(List<Definition> definitions, List<Feedback> feedback) {
        public Capture {
            definitions = List.copyOf(definitions);
            feedback = List.copyOf(feedback);
        }
    }

    private final Queue<ChatResponse> script = new ArrayDeque<>();
    private final List<Capture> captures = new ArrayList<>();
    private final Redactor redactor;

    public ToolAcceptanceModel(Redactor redactor) {
        this.redactor = java.util.Objects.requireNonNull(redactor);
    }

    public synchronized ToolAcceptanceModel thenCall(String id, String name, String arguments) {
        script.add(
                new ChatResponse(
                        List.of(
                                new Generation(
                                        AssistantMessage.builder()
                                                .content("")
                                                .toolCalls(
                                                        List.of(
                                                                new AssistantMessage.ToolCall(
                                                                        id,
                                                                        "function",
                                                                        name,
                                                                        arguments)))
                                                .build()))));
        return this;
    }

    public synchronized ToolAcceptanceModel thenAnswer(String answer) {
        script.add(new ChatResponse(List.of(new Generation(new AssistantMessage(answer)))));
        return this;
    }

    @Override
    public synchronized ChatResponse call(Prompt prompt) {
        List<Definition> definitions = new ArrayList<>();
        if (prompt.getOptions() instanceof ToolCallingChatOptions options) {
            options.getToolCallbacks()
                    .forEach(callback -> {
                        var definition = callback.getToolDefinition();
                        definitions.add(
                                new Definition(
                                        definition.name(),
                                        redactor.summarize(definition.description(), 4000),
                                        definition.inputSchema()));
                    });
        }
        List<Feedback> feedback = new ArrayList<>();
        prompt.getInstructions().stream()
                .filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast)
                .flatMap(message -> message.getResponses().stream())
                .forEach(response ->
                        feedback.add(
                                new Feedback(
                                        response.id(),
                                        response.name(),
                                        redactor.summarize(response.responseData(), 10000))));
        captures.add(new Capture(definitions, feedback));
        ChatResponse response = script.poll();
        if (response == null) {
            throw new IllegalStateException("测试模型脚本已耗尽");
        }
        return response;
    }

    public synchronized List<Capture> captures() {
        return List.copyOf(captures);
    }
}
