package com.agentos.core.turn;

/**
 * 发布过程与数据事件的端口：分配序号、串行投递给本轮的 {@link TurnSink}。
 *
 * <p>工具模块经 {@code ToolContext} 的 {@link ToolContextKeys#TURN_EVENTS} 拿到它，只能调用 publish。
 * 控制面事件（终态）只能由 {@code DefaultAgentService} 在持久化收尾后经发布器实现的 finish 发出，
 * publish 拒绝控制面负载。
 */
public interface TurnEventPublisher {

    /**
     * 发布一条事件。消费方失效或等待投递超过 hard stop 时抛 {@link LeaseLostException}，
     * 调用方按输出端失效终止本轮。
     */
    void publish(TurnEventPayload payload);
}
