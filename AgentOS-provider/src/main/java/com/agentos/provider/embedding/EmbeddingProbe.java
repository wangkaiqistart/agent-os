package com.agentos.provider.embedding;

import com.agentos.core.provider.FailureKind;
import com.agentos.provider.chat.FailureClassifier;
import com.openai.errors.OpenAIServiceException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;

/**
 * 向量化的校验请求：向量化一次 "ping"，比对返回向量的长度与配置的维度。
 *
 * <p>只返回校验结果，不修改任何状态，状态由 {@link DefaultEmbeddingService} 决定怎么转换。
 * 失败原因按 research R12 分类：连不上、鉴权失败、欠费、其他。
 */
final class EmbeddingProbe {

    private static final String PING = "ping";

    /** 服务端欠费时错误码里带的字样（阿里云百炼实测：HTTP 400，code 为 Arrearage）。 */
    private static final String ARREARS_MARK = "Arrearage";

    private static final int MAX_CAUSE_DEPTH = 8;

    /** 校验结果的分类。 */
    enum Outcome {
        OK,
        DIMENSION_MISMATCH,
        UNREACHABLE,
        AUTH_FAILED,
        ARREARS,
        OTHER,
        /** 调用线程被中断（例如应用正在关闭）：不代表服务有问题，不应该改状态。 */
        CANCELLED
    }

    /**
     * 一次校验的结果。
     *
     * @param outcome 分类
     * @param actualDimensions 实际返回的向量长度；只有 OK 和 DIMENSION_MISMATCH 才有意义，否则为 0
     */
    record Result(Outcome outcome, int actualDimensions) {}

    private final OpenAiEmbeddingModel model;

    private final String modelName;

    private final int dimensions;

    private final Duration timeout;

    /**
     * @param model 向量化模型
     * @param modelName 配置的模型名，写进请求级选项，避免合并默认选项时被覆盖
     * @param dimensions 配置的维度
     * @param timeout 校验请求自己的超时，要比模型的连接超时更严格时才有意义
     */
    EmbeddingProbe(OpenAiEmbeddingModel model, String modelName, int dimensions, Duration timeout) {
        this.model = model;
        this.dimensions = dimensions;
        this.timeout = timeout;
        this.modelName = modelName;
    }

    /** 发出校验请求。任何失败都转成 {@link Result}，不向外抛。 */
    Result probe() {
        OpenAiEmbeddingOptions options =
                OpenAiEmbeddingOptions.builder()
                        .model(modelName)
                        .dimensions(dimensions)
                        .timeout(timeout)
                        .build();
        try {
            EmbeddingResponse response = model.call(new EmbeddingRequest(List.of(PING), options));
            if (response.getResults().isEmpty()) {
                return new Result(Outcome.OTHER, 0);
            }
            int actual = response.getResults().getFirst().getOutput().length;
            return new Result(
                    actual == dimensions ? Outcome.OK : Outcome.DIMENSION_MISMATCH, actual);
        } catch (RuntimeException error) {
            return new Result(classify(error), 0);
        }
    }

    private static Outcome classify(RuntimeException error) {
        FailureClassifier.Classification failure = FailureClassifier.classify(error);
        if (failure.kind() == FailureKind.CANCELLED) {
            return Outcome.CANCELLED;
        }
        if (failure.kind() == FailureKind.AUTH) {
            return Outcome.AUTH_FAILED;
        }
        if (isArrears(error)) {
            return Outcome.ARREARS;
        }
        if (failure.kind() == FailureKind.CONNECTION) {
            return Outcome.UNREACHABLE;
        }
        return Outcome.OTHER;
    }

    /** 沿异常链找 SDK 的服务异常，看它的错误码或类型里有没有欠费字样。 */
    private static boolean isArrears(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof OpenAIServiceException service
                    && (contains(service.code()) || contains(service.type()))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean contains(Optional<String> value) {
        return value.map(text -> text.contains(ARREARS_MARK)).orElse(false);
    }
}
