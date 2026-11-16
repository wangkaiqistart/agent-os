package com.agentos.core.turn;

import java.util.Objects;

/**
 * 执行过程向渠道输出事件的端口：Core 只发布事件，不直接操作 HTTP 或标准输出。
 *
 * <p>回调可能来自轮次线程或模型流的回调线程，不能依赖 Controller 线程的局部上下文。
 * 循环发布文本/工具过程事件，AgentService 在持久化收尾后唯一决定终结通知。
 */
public interface TurnSink {
    /** 为 true 时使用 ProviderService.stream；默认同步，避免无意产生增量输出。 */
    default boolean streaming() {
        return false;
    }

    /** 流式文本增量，可能触发多次；当前片段不代表最终完整答案。 */
    default void onDelta(String text) {}

    /** 通知渠道本次模型要求的工具，参数已由循环脱敏，仍不应记录日志。 */
    default void onToolCall(String toolCallId, String name, String arguments) {}

    /** 通知渠道本批正常完成后的安全工具结果，按原始调用 ID 对应。 */
    default void onToolResult(String toolCallId, String name, String result) {}

    /** 轮次成功收尾后的结果通知，发生在持久化收尾之后。 */
    default void onComplete(TurnResult result) {}

    /** 轮次以失败或超时等非成功状态收尾后的通知。 */
    default void onError(TurnResult result) {}

    /** 不消费任何事件；调用方只读取 AgentService 的返回值时使用。 */
    TurnSink NOOP = new TurnSink() {};

    static TurnSink require(TurnSink sink) {
        return Objects.requireNonNull(sink, "sink");
    }
}
