package com.agentos.core.turn;

import java.util.Objects;

/**
 * 执行过程向渠道输出事件的端口：Core 只发布事件，不直接操作 HTTP 或标准输出。
 *
 * <p>核心只调用 {@link #onEvent(TurnEvent)}：发布器按序号串行投递本轮全部事件，同一时刻只有一个线程调用，
 * 但调用线程可能是轮次线程、模型流的回调线程或工具的执行线程，不能依赖 Controller 线程的局部上下文。
 * 终态事件由 AgentService 在持久化收尾后唯一发出。契约见 specs/007-run-event-stream/contracts/run-events.md。
 *
 * <p>其余 5 个回调是兼容层：{@code onEvent} 的默认实现把对应事件翻译成它们，旧的输出端不改也能工作。
 * 新的输出端应直接覆写 {@code onEvent}。
 */
public interface TurnSink {
    /** 为 true 时使用 ProviderService.stream；默认同步，避免无意产生增量输出。 */
    default boolean streaming() {
        return false;
    }

    /**
     * 按 seq 顺序收到本轮的一条事件；抛出异常即视为输出端失效，本轮按中断收尾。
     *
     * <p>默认实现只把文字增量、工具开始 / 结束和终态翻译成旧回调，其他类型忽略。
     */
    default void onEvent(TurnEvent event) {
        switch (event.payload()) {
            case TurnEventPayload.MessageDelta delta -> onDelta(delta.text());
            case TurnEventPayload.ToolStarted started ->
                    onToolCall(
                            started.toolCallId(),
                            started.toolName(),
                            started.argumentsSummary() == null ? "" : started.argumentsSummary());
            case TurnEventPayload.ToolFinished finished ->
                    onToolResult(
                            finished.toolCallId(),
                            finished.toolName(),
                            finished.resultSummary() == null ? "" : finished.resultSummary());
            case TurnEventPayload.RunCompleted completed ->
                    onComplete(
                            new TurnResult(
                                    event.turnId(),
                                    TurnStatus.SUCCEEDED,
                                    completed.text(),
                                    completed.iterations(),
                                    completed.persistence()));
            case TurnEventPayload.RunFailed failed ->
                    onError(
                            new TurnResult(
                                    event.turnId(),
                                    failed.status(),
                                    failed.message(),
                                    failed.iterations(),
                                    failed.persistence(),
                                    failed.code()));
            case TurnEventPayload.RunCancelled cancelled ->
                    onError(
                            new TurnResult(
                                    event.turnId(),
                                    TurnStatus.CANCELLED,
                                    cancelled.message(),
                                    cancelled.iterations(),
                                    cancelled.persistence()));
            default -> {
                // 其他事件类型旧回调没有对应项，兼容层忽略。
            }
        }
    }

    /** 兼容层：流式文本增量，可能触发多次；当前片段不代表最终完整答案。 */
    default void onDelta(String text) {}

    /** 兼容层：单个工具开始执行，参数已脱敏，仍不应记录日志。 */
    default void onToolCall(String toolCallId, String name, String arguments) {}

    /** 兼容层：单个工具结束（含失败、拒绝、跳过），结果已脱敏，按原始调用 ID 对应。 */
    default void onToolResult(String toolCallId, String name, String result) {}

    /** 兼容层：轮次成功收尾后的结果通知，发生在持久化收尾之后。 */
    default void onComplete(TurnResult result) {}

    /** 兼容层：轮次以失败、超时或取消等非成功状态收尾后的通知。 */
    default void onError(TurnResult result) {}

    /** 不消费任何事件；调用方只读取 AgentService 的返回值时使用。 */
    TurnSink NOOP = new TurnSink() {};

    static TurnSink require(TurnSink sink) {
        return Objects.requireNonNull(sink, "sink");
    }
}
