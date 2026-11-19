package com.agentos.core.turn;

/**
 * 所有渠道共用的对话入口，输入与输出端口均不依赖 Servlet 或终端类型。
 *
 * <p>调用会等待本轮处理结束。加载 Agent 或创建轮次之前的错误可能直接抛出，
 * 已开始轮次的执行失败通常通过 TurnResult 表达；调用方不能只看是否抛异常判断成功。
 */
public interface AgentService {
    /** 不需要过程事件的便捷入口，默认使用空输出端。 */
    TurnResult process(ProcessRequest request);

    /** 使用 sink 接收增量及终态；是否启用模型流式由 sink.streaming 决定。 */
    TurnResult process(ProcessRequest request, TurnSink sink);

    /** 显式入口能力，兼容旧实现的无人值守语义。 */
    default TurnResult process(
            ProcessRequest request,
            TurnSink sink,
            com.agentos.core.interaction.TurnRuntimeCapabilities capabilities) {
        return process(request, sink);
    }
}
