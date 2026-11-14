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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.EmptyRateLimit;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.RateLimit;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * 按名称选择连接并调用一次聊天模型，对暂时性故障最多追加 1 次重试。
 *
 * <p>本类不执行工具、不发起下一次模型调用。每次实际发出的请求结束后，立刻把调用记录交给 {@link LlmCallRecorder}，
 * 再决定是重试还是把结果、异常交给调用方。失败分类由 {@link FailureClassifier} 负责。
 */
public class DefaultProviderService implements ProviderService {

    private static final Logger log = LoggerFactory.getLogger(DefaultProviderService.class);

    private static final String PROTOCOL_SUMMARY = "PROTOCOL 响应没有候选结果";

    /** 请求超时的下限：HTTP 客户端把 0 当作"不限时"，剩余时间极短时不能传 0。 */
    private static final Duration MIN_REQUEST_TIMEOUT = Duration.ofMillis(1);

    private final RequestValidator validator;

    private final LlmCallRecorder recorder;

    public DefaultProviderService(RequestValidator validator, LlmCallRecorder recorder) {
        this.validator = Objects.requireNonNull(validator, "validator");
        this.recorder = Objects.requireNonNull(recorder, "recorder");
    }

    @Override
    public ProviderResult call(ProviderRequest request) {
        return execute(request, CallMode.CALL, text -> {});
    }

    @Override
    public ProviderResult stream(ProviderRequest request, Consumer<String> textSink) {
        Objects.requireNonNull(textSink, "textSink");
        return execute(request, CallMode.STREAM, textSink);
    }

    /** 同步和流式共用的执行链路：校验、预算、最多 2 次尝试、每次尝试一条记录。 */
    private ProviderResult execute(
            ProviderRequest request, CallMode mode, Consumer<String> textSink) {
        Budget budget = Budget.of(request.deadline());
        // 截止时间已过是运行时超时：不发请求、不产生调用记录。
        if (budget.exhausted()) {
            throw new ProviderCallException("已超过本轮截止时间", List.of());
        }
        RequestValidator.Resolved resolved = validator.resolve(request.profile());
        // 非图片媒体在这里外呼前失败；同时拿到图片数量写进每条记录。
        int imageCount = RequestValidator.countImages(request.messages());

        Attempt first = attempt(request, resolved, budget, 1, mode, imageCount, textSink);
        if (first.failure() == null) {
            return new ProviderResult(first.response(), List.of(first.record()));
        }
        // 流式已经把增量交给了输出端：重发会让调用方收到重复内容，不重试（FR-019）。
        if (first.delivered() || !shouldRetry(first.failure(), resolved.connection(), budget)) {
            throw failed(List.of(first.record()), first.failure());
        }
        if (!pause(first.failure().retryAfter())) {
            // 等待期间被中断：没有发出第 2 次请求，所以只有第 1 次的记录。
            throw failed(List.of(first.record()), FailureClassifier.cancelled());
        }
        // 等待后用单调时钟复核预算，预算用完就不再发请求。
        if (budget.exhausted()) {
            throw failed(List.of(first.record()), first.failure());
        }

        Attempt second = attempt(request, resolved, budget, 2, mode, imageCount, textSink);
        if (second.failure() == null) {
            return new ProviderResult(second.response(), List.of(first.record(), second.record()));
        }
        throw failed(List.of(first.record(), second.record()), second.failure());
    }

    /**
     * 判断失败后要不要等待并重试（FR-017、FR-018）。
     *
     * <p>有截止时间：预计"等完 Retry-After 后的剩余时间"小于连接的 retry-min-remaining 就放弃，这一条同时 覆盖了 Retry-After
     * 超过剩余预算的情况。没有截止时间：Retry-After 超过 retry-max-wait 就放弃， 避免无限期阻塞调用线程。
     */
    private static boolean shouldRetry(
            FailureClassifier.Classification failure,
            ProviderConnection connection,
            Budget budget) {
        if (!failure.retryable()) {
            return false;
        }
        Duration wait = failure.retryAfter();
        if (budget.bounded()) {
            return budget.remaining().minus(wait).compareTo(connection.retryMinRemaining()) >= 0;
        }
        return wait.compareTo(connection.retryMaxWait()) <= 0;
    }

    /** 等待重试间隔。被中断时恢复中断标志并返回 false。 */
    private static boolean pause(Duration wait) {
        try {
            Thread.sleep(wait);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 失败时抛出的统一异常：只带脱敏摘要和全部记录，不带原始异常。 */
    private static ProviderCallException failed(
            List<LlmCallRecord> calls, FailureClassifier.Classification failure) {
        return new ProviderCallException("模型调用失败：" + failure.summary(), calls);
    }

    /** 发出一次实际请求，结束后立刻交给记录输出，再把结果返回给调用逻辑。 */
    private Attempt attempt(
            ProviderRequest request,
            RequestValidator.Resolved resolved,
            Budget budget,
            int attemptNumber,
            CallMode mode,
            int imageCount,
            Consumer<String> textSink) {
        Prompt prompt =
                new Prompt(request.messages(), buildOptions(request, resolved, budget, mode));
        // 每次尝试一个收集器：是否已交出增量是按尝试记的。同步调用不用它。
        StreamCollector collector = new StreamCollector(textSink);
        Instant startedAt = Instant.now();
        long startNanos = System.nanoTime();
        ChatResponse response = null;
        FailureClassifier.Classification failure = null;
        try {
            response =
                    mode == CallMode.STREAM
                            ? collector.collect(
                                    resolved.connection().chatModel().stream(prompt),
                                    budget.bounded() ? budget::remaining : null)
                            : resolved.connection().chatModel().call(prompt);
        } catch (RuntimeException error) {
            failure = classify(error, collector);
        }
        long durationMs = elapsedMs(startNanos);

        if (failure == null && (response == null || response.getResults().isEmpty())) {
            failure = FailureClassifier.protocol(PROTOCOL_SUMMARY);
        }
        LlmCallRecord record =
                failure == null
                        ? successRecord(
                                request,
                                resolved,
                                attemptNumber,
                                mode,
                                startedAt,
                                durationMs,
                                response,
                                imageCount,
                                mode == CallMode.CALL || collector.usageReported())
                        : failureRecord(
                                request,
                                resolved,
                                attemptNumber,
                                mode,
                                startedAt,
                                durationMs,
                                failure,
                                imageCount);
        if (Boolean.FALSE.equals(record.outputCapHonored())) {
            // 告警由本类发出，与记录输出的实现无关；只写数字，不写模型输出。
            log.warn(
                    "模型没有遵守输出上限：provider={}, model={}, cap={}, completionTokens={}",
                    record.provider(),
                    record.requestedModel(),
                    request.profile().maxOutputTokens(),
                    record.completionTokens());
        }
        recorder.record(record);
        return new Attempt(record, response, failure, collector.delivered());
    }

    /**
     * 给异常分类。输出端自己抛的异常是调用方取消，不是模型服务的问题；
     * 线程被中断导致的取消要恢复中断标志，因为响应式阻塞等待抛出异常时已经把它清掉了。
     */
    private static FailureClassifier.Classification classify(
            RuntimeException error, StreamCollector collector) {
        if (collector.sinkFailed()) {
            return FailureClassifier.cancelled();
        }
        FailureClassifier.Classification failure = FailureClassifier.classify(error);
        if (failure.kind() == FailureKind.CANCELLED) {
            Thread.currentThread().interrupt();
        } else if (failure.kind() == FailureKind.PROTOCOL) {
            // 不认识的异常只记类名，不记 message，避免响应内容进日志。
            log.warn("模型调用抛出未识别的异常：{}", error.getClass().getName());
        }
        return failure;
    }

    /** 只写 Profile 设置过的字段，没设置的不补默认值（FR-008）。 */
    private static OpenAiChatOptions buildOptions(
            ProviderRequest request,
            RequestValidator.Resolved resolved,
            Budget budget,
            CallMode mode) {
        var profile = request.profile();
        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder();
        options.model(resolved.model());
        if (mode == CallMode.STREAM) {
            // 流式默认不返回用量，必须显式要求，否则记录里的 token 全是空。
            options.streamUsage(true);
        }
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
        options.timeout(requestTimeout(resolved.connection().timeout(), budget));
        return options.build();
    }

    private static Duration requestTimeout(Duration connectionTimeout, Budget budget) {
        if (!budget.bounded()) {
            return connectionTimeout;
        }
        Duration remaining = budget.remaining();
        Duration smaller =
                remaining.compareTo(connectionTimeout) < 0 ? remaining : connectionTimeout;
        return smaller.compareTo(MIN_REQUEST_TIMEOUT) < 0 ? MIN_REQUEST_TIMEOUT : smaller;
    }

    private static long elapsedMs(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    }

    private static LlmCallRecord successRecord(
            ProviderRequest request,
            RequestValidator.Resolved resolved,
            int attemptNumber,
            CallMode mode,
            Instant startedAt,
            long durationMs,
            ChatResponse response,
            int imageCount,
            boolean usageReported) {
        CallCorrelation correlation = request.correlation();
        ChatResponseMetadata metadata = response.getMetadata();
        // 调用方已确认 getResults() 非空；用 getFirst 而不是可能返回 null 的 getResult。
        Generation first = response.getResults().getFirst();
        Usage usage = metadata.getUsage();
        // EmptyUsage 表示服务端没返回用量：记为空，不能记成 0（FR-023）。
        // 流式聚合器在没收到用量时会默认成 0，所以还要看原始分片里是否真的报过。
        boolean hasUsage = usageReported && usage != null && !(usage instanceof EmptyUsage);
        // 同理：EmptyRateLimit 表示服务端没返回限流头，余量记为空。
        RateLimit rateLimit =
                metadata.getRateLimit() instanceof EmptyRateLimit ? null : metadata.getRateLimit();
        return new LlmCallRecord(
                correlation.sessionId(),
                correlation.turnId(),
                correlation.iteration(),
                resolved.connection().name(),
                resolved.model(),
                emptyToNull(metadata.getModel()),
                emptyToNull(metadata.getId()),
                mode,
                attemptNumber,
                first.getMetadata().getFinishReason(),
                first.getOutput().getToolCalls().size(),
                imageCount,
                hasUsage ? usage.getPromptTokens() : null,
                hasUsage ? usage.getCompletionTokens() : null,
                hasUsage ? usage.getTotalTokens() : null,
                hasUsage ? usage.getCacheReadInputTokens() : null,
                hasUsage ? usage.getCacheWriteInputTokens() : null,
                rateLimit == null ? null : rateLimit.getRequestsRemaining(),
                rateLimit == null ? null : rateLimit.getTokensRemaining(),
                capCanBeChecked(request.profile().maxOutputTokens(), usage, hasUsage)
                        ? usage.getCompletionTokens() <= request.profile().maxOutputTokens()
                        : null,
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
            int attemptNumber,
            CallMode mode,
            Instant startedAt,
            long durationMs,
            FailureClassifier.Classification failure,
            int imageCount) {
        CallCorrelation correlation = request.correlation();
        return new LlmCallRecord(
                correlation.sessionId(),
                correlation.turnId(),
                correlation.iteration(),
                resolved.connection().name(),
                resolved.model(),
                null,
                null,
                mode,
                attemptNumber,
                null,
                null,
                imageCount,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                failure.kind(),
                failure.httpStatus(),
                failure.summary(),
                startedAt,
                durationMs);
    }

    /** Profile 设了上限且服务端返回了输出用量时，才有"是否遵守"可言（FR-009）。 */
    private static boolean capCanBeChecked(Integer cap, Usage usage, boolean hasUsage) {
        return cap != null && hasUsage && usage.getCompletionTokens() != null;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    /** 一次尝试的结果：成功时 failure 为 null，失败时 response 可能为 null。 */
    private record Attempt(
            LlmCallRecord record,
            ChatResponse response,
            FailureClassifier.Classification failure,
            boolean delivered) {}

    /**
     * 本轮剩余时间的预算。
     *
     * <p>调用开始时用墙钟算出剩余时间，之后只用单调时钟（{@link System#nanoTime()}）推进，
     * 这样系统时间被调整也不会让等待和超时算错。没有截止时间时不受限（{@link #bounded()} 为 false）。
     */
    private static final class Budget {

        private final Duration total;

        private final long startNanos;

        private Budget(Duration total) {
            this.total = total;
            this.startNanos = System.nanoTime();
        }

        static Budget of(Instant deadline) {
            return new Budget(deadline == null ? null : Duration.between(Instant.now(), deadline));
        }

        boolean bounded() {
            return total != null;
        }

        /** 只在 {@link #bounded()} 为 true 时调用。 */
        Duration remaining() {
            return total.minus(Duration.ofNanos(System.nanoTime() - startNanos));
        }

        boolean exhausted() {
            return bounded() && !remaining().isPositive();
        }
    }
}
