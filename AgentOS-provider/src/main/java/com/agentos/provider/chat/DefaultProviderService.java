package com.agentos.provider.chat;

import com.agentos.core.provider.CallCorrelation;
import com.agentos.core.provider.CallMode;
import com.agentos.core.provider.FailureKind;
import com.agentos.core.provider.LlmCallRecord;
import com.agentos.core.provider.LlmCallRecorder;
import com.agentos.core.provider.ProviderCallException;
import com.agentos.core.provider.ProviderRequest;
import com.agentos.core.provider.ProviderResult;
import com.agentos.core.provider.ProviderService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * 按名称选择连接并调用一次聊天模型。
 *
 * <p>本类只做单次尝试：不执行工具、不发起下一次模型调用。重试、失败分类和流式调用由后续任务补充。
 * 每次尝试结束后、把结果或异常交给调用方之前，立刻把调用记录交给 {@link LlmCallRecorder}。
 */
public class DefaultProviderService implements ProviderService {

    private static final String PROTOCOL_SUMMARY = "PROTOCOL 响应没有候选结果";

    private final RequestValidator validator;

    private final LlmCallRecorder recorder;

    public DefaultProviderService(RequestValidator validator, LlmCallRecorder recorder) {
        this.validator = Objects.requireNonNull(validator, "validator");
        this.recorder = Objects.requireNonNull(recorder, "recorder");
    }

    @Override
    public ProviderResult call(ProviderRequest request) {
        Instant deadline = request.deadline();
        // 截止时间已过是运行时超时：不发请求、不产生调用记录。
        if (deadline != null && !deadline.isAfter(Instant.now())) {
            throw new ProviderCallException("已超过本轮截止时间", List.of());
        }
        RequestValidator.Resolved resolved = validator.resolve(request.profile());
        ProviderConnection connection = resolved.connection();
        Prompt prompt = new Prompt(request.messages(), buildOptions(request, resolved));

        Instant startedAt = Instant.now();
        long startNanos = System.nanoTime();
        ChatResponse response = connection.chatModel().call(prompt);
        long durationMs = elapsedMs(startNanos);

        if (response == null || response.getResults().isEmpty()) {
            LlmCallRecord failed =
                    failureRecord(request, resolved, startedAt, durationMs, FailureKind.PROTOCOL);
            recorder.record(failed);
            throw new ProviderCallException("模型响应没有候选结果", List.of(failed));
        }
        LlmCallRecord success = successRecord(request, resolved, startedAt, durationMs, response);
        recorder.record(success);
        return new ProviderResult(response, List.of(success));
    }

    @Override
    public ProviderResult stream(ProviderRequest request, Consumer<String> textSink) {
        // 流式调用在 spec 的 US4 实现。
        throw new UnsupportedOperationException("流式调用尚未实现");
    }

    /** 只写 Profile 设置过的字段，没设置的不补默认值（FR-008）。 */
    private static OpenAiChatOptions buildOptions(
            ProviderRequest request, RequestValidator.Resolved resolved) {
        var profile = request.profile();
        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder();
        options.model(resolved.model());
        if (profile.reasoningEffort() != null) {
            options.reasoningEffort(profile.reasoningEffort());
        }
        resolved.connection().type().applyOutputLimit(options, profile.maxOutputTokens());
        if (profile.temperature() != null) {
            options.temperature(profile.temperature());
        }
        // 没有工具时不写 toolCallbacks，请求里就不会出现 tools 字段（FR-010）。
        if (!request.tools().isEmpty()) {
            options.toolCallbacks(request.tools());
        }
        // toolContext 只交给工具，不进请求 JSON（FR-011）。
        if (!request.toolContext().isEmpty()) {
            options.toolContext(request.toolContext());
        }
        // 单次超时取连接超时与本轮剩余时间的较小值（FR-012）。
        options.timeout(effectiveTimeout(resolved.connection().timeout(), request.deadline()));
        return options.build();
    }

    private static Duration effectiveTimeout(Duration connectionTimeout, Instant deadline) {
        if (deadline == null) {
            return connectionTimeout;
        }
        Duration remaining = Duration.between(Instant.now(), deadline);
        return remaining.compareTo(connectionTimeout) < 0 ? remaining : connectionTimeout;
    }

    private static long elapsedMs(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    }

    private static LlmCallRecord successRecord(
            ProviderRequest request,
            RequestValidator.Resolved resolved,
            Instant startedAt,
            long durationMs,
            ChatResponse response) {
        CallCorrelation correlation = request.correlation();
        ChatResponseMetadata metadata = response.getMetadata();
        // 调用方已确认 getResults() 非空；用 getFirst 而不是可能返回 null 的 getResult。
        Generation first = response.getResults().getFirst();
        Usage usage = metadata.getUsage();
        // EmptyUsage 表示服务端没返回用量：记为空，不能记成 0（FR-023）。
        boolean hasUsage = usage != null && !(usage instanceof EmptyUsage);
        return new LlmCallRecord(
                correlation.sessionId(),
                correlation.turnId(),
                correlation.iteration(),
                resolved.connection().name(),
                resolved.model(),
                emptyToNull(metadata.getModel()),
                emptyToNull(metadata.getId()),
                CallMode.CALL,
                1,
                first.getMetadata().getFinishReason(),
                first.getOutput().getToolCalls().size(),
                // TODO(US6)：RequestValidator 统计图片数量后，这里改为实际值。
                0,
                hasUsage ? usage.getPromptTokens() : null,
                hasUsage ? usage.getCompletionTokens() : null,
                hasUsage ? usage.getTotalTokens() : null,
                hasUsage ? usage.getCacheReadInputTokens() : null,
                hasUsage ? usage.getCacheWriteInputTokens() : null,
                // 限流余量与 outputCapHonored 在 US3 补齐。
                null,
                null,
                null,
                true,
                null,
                null,
                null,
                startedAt,
                durationMs);
    }

    private static LlmCallRecord failureRecord(
            ProviderRequest request,
            RequestValidator.Resolved resolved,
            Instant startedAt,
            long durationMs,
            FailureKind kind) {
        CallCorrelation correlation = request.correlation();
        return new LlmCallRecord(
                correlation.sessionId(),
                correlation.turnId(),
                correlation.iteration(),
                resolved.connection().name(),
                resolved.model(),
                null,
                null,
                CallMode.CALL,
                1,
                null,
                null,
                0,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                kind,
                null,
                PROTOCOL_SUMMARY,
                startedAt,
                durationMs);
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
