package com.agentos.provider.chat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.MessageAggregator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 收集一次流式调用：把文本增量按到达顺序交给输出端，流结束时交出聚合后的完整结果。
 *
 * <p>一个实例只服务一次尝试，因为"是否已交出增量"是按尝试记的：交出过增量就不能重试（FR-019）。
 * 增量以输出端是否收到过非空文本为准，只带角色或工具调用片段的分片不算。
 *
 * <p>工具调用的参数被拆在多个分片里时，由 SDK 与 {@link MessageAggregator} 拼完整后才出现在结果里， 本类不执行工具。
 */
final class StreamCollector {

    private final Consumer<String> sink;

    /** 增量在响应式线程上交出、在调用线程上读取，所以用 volatile。 */
    private volatile boolean delivered;

    private volatile boolean sinkFailed;

    private volatile boolean usageReported;

    StreamCollector(Consumer<String> sink) {
        this.sink = sink;
    }

    /** 是否已经向输出端交出过文本增量。 */
    boolean delivered() {
        return delivered;
    }

    /**
     * 服务端是否在流里返回过用量。
     *
     * <p>聚合器在没收到用量时会把 token 默认成 0，聚合后的结果分不清"报了 0"和"没报"，
     * 所以要在聚合之前看原始分片（FR-023：没返回的用量记为空，不能记成 0）。
     */
    boolean usageReported() {
        return usageReported;
    }

    /** 失败是不是输出端自己抛的异常：这是调用方取消，不是模型服务的问题。 */
    boolean sinkFailed() {
        return sinkFailed;
    }

    /**
     * 订阅并阻塞到流结束。
     *
     * @param stream 模型返回的分片流
     * @param remaining 本轮剩余时间；为 null 表示没有截止时间。超过它仍没结束就取消订阅并抛出
     *     {@link java.util.concurrent.TimeoutException}，不能只依赖 SDK 的读超时
     * @return 聚合后的结果；流没有正常结束时抛出原始异常
     */
    ChatResponse collect(Flux<ChatResponse> stream, Supplier<Duration> remaining) {
        AtomicReference<ChatResponse> aggregated = new AtomicReference<>();
        Flux<ChatResponse> pipeline =
                new MessageAggregator()
                        .aggregate(stream.doOnNext(this::observeUsage), aggregated::set)
                        .doOnNext(this::deliver);
        if (remaining != null) {
            // 每个分片到达后重新按剩余时间计时，等价于整个流共用一个绝对截止时间。
            pipeline =
                    pipeline.timeout(
                            Mono.delay(nonNegative(remaining.get())),
                            chunk -> Mono.delay(nonNegative(remaining.get())));
        }
        pipeline.blockLast();
        return aggregated.get();
    }

    private void observeUsage(ChatResponse chunk) {
        Usage usage = chunk.getMetadata().getUsage();
        if (usage == null || usage instanceof EmptyUsage) {
            return;
        }
        // 实测流式下没带用量的分片也是 DefaultUsage，三项全为 0，不是 EmptyUsage，只能按数值判断。
        // 代价：服务端在流式里明确报"全 0"会被当成没报；真实调用里有输入就一定大于 0，可以接受。
        if (positive(usage.getPromptTokens())
                || positive(usage.getCompletionTokens())
                || positive(usage.getTotalTokens())) {
            usageReported = true;
        }
    }

    private void deliver(ChatResponse chunk) {
        // 只带用量的分片 choices 为空，没有文本。
        if (chunk.getResults().isEmpty()) {
            return;
        }
        String text = chunk.getResults().getFirst().getOutput().getText();
        if (text == null || text.isEmpty()) {
            return;
        }
        delivered = true;
        try {
            sink.accept(text);
        } catch (RuntimeException error) {
            sinkFailed = true;
            throw error;
        }
    }

    private static boolean positive(Integer tokens) {
        return tokens != null && tokens > 0;
    }

    private static Duration nonNegative(Duration value) {
        return value.isNegative() ? Duration.ZERO : value;
    }
}
