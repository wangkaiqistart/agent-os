package com.agentos.provider.embedding;

import com.agentos.core.error.AgentOSException;
import com.agentos.core.error.ErrorCode;
import com.agentos.core.provider.FailureKind;
import com.agentos.core.provider.embedding.EmbeddingService;
import com.agentos.core.provider.embedding.EmbeddingStatus;
import com.agentos.provider.chat.FailureClassifier;
import com.agentos.provider.config.EmbeddingProperties;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;

/**
 * 全局唯一的文本向量化服务。
 *
 * <p>内部至多持有一个 {@link OpenAiEmbeddingModel}，且不注册成 Bean：禁用时一个都不创建（FR-030、FR-031）。
 * 自己按 {@code max-batch-size} 分批（SDK 不分批，百炼单次超过 10 条会报 400）、
 * 每批最多重试 1 次、检查每个向量的长度。状态用 {@link AtomicReference} 保存，校验和调用并发时不需要加锁。
 *
 * <p>启动校验的触发时机由 {@link EmbeddingStartupValidator} 决定，本类只提供 {@link #validate()}。
 */
public final class DefaultEmbeddingService implements EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(DefaultEmbeddingService.class);

    /** 校验请求超时的上限（research R12）。 */
    private static final Duration MAX_PROBE_TIMEOUT = Duration.ofSeconds(10);

    /** 鉴权失败的原因文字：key 来自环境变量，运行期间不会变，所以这个状态不再重新校验。 */
    static final String REASON_AUTH = "鉴权失败";

    private static final String REASON_UNREACHABLE = "不可达";

    private static final String REASON_ARREARS = "欠费";

    private static final String REASON_OTHER = "其他";

    private final OpenAiEmbeddingModel model;

    private final String modelName;

    private final int dimensions;

    private final int maxBatchSize;

    private final Duration timeout;

    private final EmbeddingProbe probe;

    /** 校验请求的超时，也是状态为 CHECKING 时调用最多等待的时间。 */
    private final Duration probeTimeout;

    private final AtomicReference<EmbeddingStatus> status;

    /** 第一次校验完成时结束；状态为 CHECKING 的调用靠它等结果。 */
    private final CompletableFuture<Void> firstValidation = new CompletableFuture<>();

    private DefaultEmbeddingService(
            OpenAiEmbeddingModel model, EmbeddingProperties properties, EmbeddingStatus initial) {
        this.model = model;
        this.modelName = properties.getModel();
        this.dimensions = properties.getDimensions();
        this.maxBatchSize = properties.getMaxBatchSize();
        this.timeout = properties.getTimeout();
        this.status = new AtomicReference<>(initial);
        this.probeTimeout = timeout.compareTo(MAX_PROBE_TIMEOUT) < 0 ? timeout : MAX_PROBE_TIMEOUT;
        this.probe =
                model == null
                        ? null
                        : new EmbeddingProbe(model, modelName, dimensions, probeTimeout);
        if (model == null) {
            // 没有模型就没有校验可做。
            firstValidation.complete(null);
        }
    }

    /**
     * 按配置创建服务。未启用或 key 为空时不创建模型，状态为 DISABLED。
     *
     * <p>创建模型只构造 SDK 客户端，不发任何网络请求。
     */
    public static DefaultEmbeddingService create(
            EmbeddingProperties properties, ObservationRegistry observationRegistry) {
        if (!properties.isEnabled()) {
            return new DefaultEmbeddingService(
                    null, properties, EmbeddingStatus.disabled("agentos.embedding.enabled=false"));
        }
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            return new DefaultEmbeddingService(
                    null, properties, EmbeddingStatus.disabled("缺少 API key"));
        }
        return new DefaultEmbeddingService(
                buildModel(properties, observationRegistry),
                properties,
                EmbeddingStatus.checking());
    }

    /** 连接级选项只放地址、密钥、模型、维度、超时和关闭 SDK 重试；重试归本类（research R4、R13）。 */
    static OpenAiEmbeddingModel buildModel(
            EmbeddingProperties properties, ObservationRegistry observationRegistry) {
        OpenAiEmbeddingOptions options =
                OpenAiEmbeddingOptions.builder()
                        .baseUrl(properties.getBaseUrl())
                        .apiKey(properties.getApiKey())
                        .model(properties.getModel())
                        .dimensions(properties.getDimensions())
                        .timeout(properties.getTimeout())
                        .maxRetries(0)
                        .build();
        return OpenAiEmbeddingModel.builder()
                .options(options)
                .observationRegistry(observationRegistry)
                .build();
    }

    /** 发一次校验请求并按结果转换状态。同步校验直接调它，后台校验由启动校验器在虚拟线程上调它。 */
    public EmbeddingStatus validate() {
        if (probe == null) {
            return status.get();
        }
        EmbeddingProbe.Result result = probe.probe();
        apply(result);
        firstValidation.complete(null);
        return status.get();
    }

    private void apply(EmbeddingProbe.Result result) {
        switch (result.outcome()) {
            case OK -> transition(EmbeddingStatus.ready());
            case DIMENSION_MISMATCH -> misconfigured(result.actualDimensions());
            case UNREACHABLE -> transition(EmbeddingStatus.unavailable(REASON_UNREACHABLE));
            case AUTH_FAILED -> transition(EmbeddingStatus.unavailable(REASON_AUTH));
            case ARREARS -> transition(EmbeddingStatus.unavailable(REASON_ARREARS));
            case OTHER -> transition(EmbeddingStatus.unavailable(REASON_OTHER));
            case CANCELLED -> {
                // 线程被中断（应用在关闭）：服务本身没有问题，状态保持原样。
            }
            default -> throw new IllegalStateException("未处理的校验结果：" + result.outcome());
        }
    }

    /**
     * 转换状态。配置错误和未启用是终态，过期的校验结果不能把它们覆盖回可用。
     *
     * <p>用 {@code getAndUpdate} 拿到转换前的状态，只在真的发生变化时打日志，避免重复刷屏。
     */
    private void transition(EmbeddingStatus next) {
        EmbeddingStatus previous =
                status.getAndUpdate(current -> isFinal(current) ? current : next);
        if (isFinal(previous) || previous.equals(next)) {
            return;
        }
        switch (next.state()) {
            case READY -> log.info("向量化校验通过：model={}, dimensions={}", modelName, dimensions);
            case UNAVAILABLE -> log.warn("向量化校验失败：{}", next.reason());
            default -> {
                // CHECKING / DISABLED 不会从这里转入。
            }
        }
    }

    private void misconfigured(int actual) {
        String reason = "配置维度 " + dimensions + "，实际 " + actual;
        EmbeddingStatus previous =
                status.getAndUpdate(current ->
                        current.state() == EmbeddingStatus.State.DISABLED
                                ? current
                                : EmbeddingStatus.misconfigured(reason));
        if (previous.state() != EmbeddingStatus.State.MISCONFIGURED
                && previous.state() != EmbeddingStatus.State.DISABLED) {
            log.error("向量化维度与配置不一致：配置 {}，实际 {}", dimensions, actual);
        }
    }

    private static boolean isFinal(EmbeddingStatus current) {
        return current.state() == EmbeddingStatus.State.MISCONFIGURED
                || current.state() == EmbeddingStatus.State.DISABLED;
    }

    /** 第一次校验还没做完。 */
    public boolean pending() {
        return status.get().state() == EmbeddingStatus.State.CHECKING;
    }

    /** 不可用且值得再试：鉴权失败不算，key 不会因为等待而变对。 */
    boolean needsRevalidation() {
        EmbeddingStatus current = status.get();
        return current.state() == EmbeddingStatus.State.UNAVAILABLE
                && !REASON_AUTH.equals(current.reason());
    }

    /** 是否创建了向量化模型；未启用时必须是 false（SC-014）。 */
    boolean hasModel() {
        return model != null;
    }

    @Override
    public EmbeddingStatus status() {
        return status.get();
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public float[] embed(String text) {
        return embed(List.of(text)).getFirst();
    }

    /**
     * 按输入顺序返回向量。空列表直接返回空结果，不发请求（FR-033）。
     *
     * <p>每批响应都检查每个向量的长度；任何一批不一致，整次调用失败、不返回任何向量（FR-032）。
     */
    @Override
    public List<float[]> embed(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }
        requireReady();
        CallStats stats = new CallStats();
        long startNanos = System.nanoTime();
        try {
            List<float[]> vectors = new ArrayList<>(texts.size());
            for (int from = 0; from < texts.size(); from += maxBatchSize) {
                int to = Math.min(from + maxBatchSize, texts.size());
                vectors.addAll(embedBatch(texts.subList(from, to), stats));
            }
            logCall(texts.size(), stats, null, startNanos);
            return List.copyOf(vectors);
        } catch (AgentOSException failure) {
            // 异常消息本来就是给调用方看的安全摘要，不含原文、向量和 key。
            logCall(texts.size(), stats, failure.getMessage(), startNanos);
            throw failure;
        }
    }

    /** 按当前状态决定能不能发请求（contracts/provider-service.md 的状态表）。 */
    private void requireReady() {
        if (status.get().state() == EmbeddingStatus.State.CHECKING) {
            awaitFirstValidation();
        }
        EmbeddingStatus current = status.get();
        switch (current.state()) {
            case READY -> {
                // 可以发请求。
            }
            case CHECKING -> throw unavailable("向量化校验中");
            case DISABLED -> throw unavailable("向量化未启用");
            case UNAVAILABLE -> throw unavailable("向量化不可用：" + current.reason());
            case MISCONFIGURED -> throw unavailable("向量化配置错误：" + current.reason());
            default -> throw new IllegalStateException("未处理的状态：" + current.state());
        }
    }

    /** 状态为 CHECKING 时最多等一个校验超时；等不到就保持 CHECKING，由调用方报"校验中"。 */
    private void awaitFirstValidation() {
        try {
            firstValidation.get(probeTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException stillChecking) {
            // 状态仍是 CHECKING，requireReady 会据此报错。
        } catch (ExecutionException neverHappens) {
            // firstValidation 只会正常完成，这里不可能出现。
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw unavailable("向量化调用被取消");
        }
    }

    private List<float[]> embedBatch(List<String> batch, CallStats stats) {
        EmbeddingResponse response = callWithRetry(batch, stats);
        if (response.getResults().size() != batch.size()) {
            throw unavailable("向量化服务返回的向量条数与请求不一致");
        }
        List<float[]> vectors = new ArrayList<>(batch.size());
        for (var embedding : response.getResults()) {
            float[] vector = embedding.getOutput();
            if (vector.length != dimensions) {
                // 服务端可能换了模型：不把维度错误的向量交给调用方，并把服务标记为配置错误。
                misconfigured(vector.length);
                throw unavailable("向量化配置错误：" + status.get().reason());
            }
            vectors.add(vector);
        }
        addTokens(stats, response);
        return vectors;
    }

    /** 最多 2 次请求，规则与聊天一致：只重试 429、可重试的 5xx、临时连接错误（FR-034）。 */
    private EmbeddingResponse callWithRetry(List<String> batch, CallStats stats) {
        FailureClassifier.Classification failure;
        try {
            return callOnce(batch, stats);
        } catch (RuntimeException error) {
            failure = classify(error);
        }
        // 向量化没有本轮截止时间，等待上限用调用超时，避免被超大的 Retry-After 无限期拖住。
        if (!failure.retryable() || failure.retryAfter().compareTo(timeout) > 0) {
            throw failed(failure);
        }
        try {
            Thread.sleep(failure.retryAfter());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw failed(FailureClassifier.cancelled());
        }
        try {
            return callOnce(batch, stats);
        } catch (RuntimeException error) {
            throw failed(classify(error));
        }
    }

    private EmbeddingResponse callOnce(List<String> batch, CallStats stats) {
        stats.requests++;
        // 请求级选项显式写模型和维度，不依赖和默认选项合并的结果（FR-032）。
        OpenAiEmbeddingOptions options =
                OpenAiEmbeddingOptions.builder().model(modelName).dimensions(dimensions).build();
        return model.call(new EmbeddingRequest(batch, options));
    }

    private static FailureClassifier.Classification classify(RuntimeException error) {
        FailureClassifier.Classification failure = FailureClassifier.classify(error);
        if (failure.kind() == FailureKind.PROTOCOL) {
            // 不认识的异常只记类名，不记 message，避免响应内容进日志。
            log.warn("向量化调用抛出未识别的异常：{}", error.getClass().getName());
        }
        return failure;
    }

    private static AgentOSException failed(FailureClassifier.Classification failure) {
        if (failure.kind() == FailureKind.CANCELLED) {
            // SDK 抛出异常时可能已经清掉了中断标志，这里确保调用方还能看到它。
            Thread.currentThread().interrupt();
            return unavailable("向量化调用被取消");
        }
        return unavailable("向量化调用失败：" + failure.summary());
    }

    private static AgentOSException unavailable(String message) {
        return new AgentOSException(ErrorCode.PROVIDER_UNAVAILABLE, message);
    }

    private static void addTokens(CallStats stats, EmbeddingResponse response) {
        Usage usage = response.getMetadata().getUsage();
        // EmptyUsage 表示服务端没返回用量：保持为空，不能记成 0（FR-023）。
        if (usage == null || usage instanceof EmptyUsage || usage.getTotalTokens() == null) {
            return;
        }
        stats.totalTokens =
                (stats.totalTokens == null ? 0 : stats.totalTokens) + usage.getTotalTokens();
    }

    /** 每次 embed 一条结构化日志（FR-035）：只有模型、条数、次数、用量、结果和耗时。 */
    private void logCall(int textCount, CallStats stats, String errorSummary, long startNanos) {
        long durationMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
        boolean success = errorSummary == null;
        (success ? log.atInfo() : log.atWarn())
                .addKeyValue("event", "embedding_call")
                .addKeyValue("model", modelName)
                .addKeyValue("textCount", textCount)
                .addKeyValue("requestCount", stats.requests)
                .addKeyValue("totalTokens", stats.totalTokens)
                .addKeyValue("success", success)
                .addKeyValue("errorSummary", errorSummary)
                .addKeyValue("durationMs", durationMs)
                .log("向量化调用完成");
    }

    /** 一次 embed 调用累计的请求次数和用量。 */
    private static final class CallStats {
        private int requests;

        private Integer totalTokens;
    }
}
