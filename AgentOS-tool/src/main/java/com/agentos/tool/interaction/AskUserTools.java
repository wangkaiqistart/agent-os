package com.agentos.tool.interaction;

import com.agentos.core.interaction.InteractionRequest;
import com.agentos.core.interaction.InteractionResult;
import com.agentos.core.interaction.TurnRuntimeCapabilities;
import com.agentos.core.tool.Redactor;
import com.agentos.core.tool.ToolExecutionScope;
import com.agentos.core.turn.ToolContextKeys;
import com.agentos.core.turn.TurnEventPayload;
import com.agentos.core.turn.TurnEventPublisher;
import java.util.Map;
import java.util.UUID;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import tools.jackson.databind.json.JsonMapper;

/** 通过当前轮次的显式入口能力提问；不读取标准输入、不创建新轮次。 */
public final class AskUserTools {
    private final InteractionToolProperties properties;
    private final Redactor redactor;

    public AskUserTools(InteractionToolProperties properties, Redactor redactor) {
        this.properties = properties;
        this.redactor = redactor;
    }

    @Tool(name = "ask_user", description = "通过当前交互入口向用户提问；无人值守立即返回不支持")
    public String ask_user(
            @ToolParam(description = "非空问题，最多4000字符") String question, ToolContext context) {
        var scope = (ToolExecutionScope) context.getContext().get(ToolContextKeys.TOOL_SCOPE);
        scope.checkpoint();
        if (question == null
                || question.isBlank()
                || question.length() > properties.maxQuestionChars()) {
            throw new IllegalArgumentException("INVALID_ARGUMENT");
        }
        var capabilities =
                (TurnRuntimeCapabilities)
                        context.getContext()
                                .getOrDefault(
                                        ToolContextKeys.RUNTIME_CAPABILITIES,
                                        TurnRuntimeCapabilities.NONE);
        var request =
                new InteractionRequest(
                        UUID.randomUUID(),
                        scope.turnId(),
                        scope.callId(),
                        redactor.redact(question),
                        scope.deadlineNanos(),
                        properties.maxAnswerChars());
        var events =
                context.getContext().get(ToolContextKeys.TURN_EVENTS)
                                instanceof TurnEventPublisher publisher
                        ? publisher
                        : null;
        if (events != null) {
            events.publish(
                    new TurnEventPayload.InputRequested(
                            request.requestId(), scope.callId(), request.question()));
        }
        scope.possiblyStarted();
        InteractionResult result = null;
        try {
            result = capabilities.interaction().ask(request, scope);
        } finally {
            publishResolved(events, request.requestId(), scope.callId(), result);
        }
        scope.checkpoint();
        if (result.status() != InteractionResult.Status.ANSWERED) {
            throw new IllegalStateException(result.status().name());
        }
        if (result.answer().length() > properties.maxAnswerChars()) {
            throw new IllegalStateException("TOO_LONG");
        }
        scope.completed();
        return JsonMapper.builder()
                .build()
                .writeValueAsString(Map.of("answer", redactor.redact(result.answer())));
    }

    /** 提问结果事件；答案正文不进事件。异常路径上发布失败不遮盖原因。 */
    private static void publishResolved(
            TurnEventPublisher events,
            UUID requestId,
            String toolCallId,
            InteractionResult result) {
        if (events == null) {
            return;
        }
        var resolved =
                new TurnEventPayload.InputResolved(
                        requestId,
                        toolCallId,
                        result == null
                                ? TurnEventPayload.InputOutcome.CANCELLED
                                : TurnEventPayload.InputOutcome.from(result.status()));
        if (result != null) {
            events.publish(resolved);
            return;
        }
        try {
            events.publish(resolved);
        } catch (RuntimeException ignored) {
            // 交互已经因取消或租约失效中断，原因由上抛的异常表达。
        }
    }
}
