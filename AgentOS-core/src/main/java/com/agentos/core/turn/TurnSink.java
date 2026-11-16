package com.agentos.core.turn;

import java.util.Objects;

/**
 * 执行过程向渠道输出事件的端口：Core 只发布事件，不直接操作 HTTP 或标准输出。
 *
 * <p>回调可能来自轮次线程或模型流的回调线程，不能依赖 Controller 线程的局部上下文。
 * 工具事件是后续能力预留；当前循环只产生文本增量和最终结果。
 */
public interface TurnSink {
    /** 为 true 时使用 ProviderService.stream；默认同步，避免无意产生增量输出。 */
    default boolean streaming() {
        return false;
    }

    /** 流式文本增量，可能触发多次；当前片段不代表最终完整答案。 */
    default void onDelta(String text) {}

    /** 预留：通知渠道即将调用哪个工具，参数可能敏感，不应直接记录日志。 */
    default void onToolCall(String toolCallId, String name, String arguments) {}

    /** 预留：通知渠道工具返回的结果。 */
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
