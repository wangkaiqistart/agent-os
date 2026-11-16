package com.agentos.core.turn;

import com.agentos.core.agent.LoadedAgent;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 传给后续工具执行链的显式轮次上下文契约，不通过 ThreadLocal 传递业务身份。
 *
 * <p>当前最小 ReActLoop 尚未把本对象接入工具执行；ProviderRequest 中目前只传入 turnId 字符串。
 *
 * @param sessionId 会话标识，关联多轮对话
 * @param turnId 当前用户消息对应的轮次标识
 * @param agent 已加载的 Agent 快照
 * @param trigger 触发来源，例如 cli 或 debug-http
 * @param iteration 当前迭代序号，0 可用于尚未开始模型调用的上下文
 * @param deadline 整轮共用的绝对截止时间
 */
public record TurnContext(
        UUID sessionId,
        UUID turnId,
        LoadedAgent agent,
        String trigger,
        int iteration,
        Instant deadline) {
    public TurnContext {
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
        turnId = Objects.requireNonNull(turnId, "turnId");
        agent = Objects.requireNonNull(agent, "agent");
        if (iteration < 0) {
            throw new IllegalArgumentException("iteration 不能为负数");
        }
        deadline = Objects.requireNonNull(deadline, "deadline");
    }

    /** 复制到指定迭代，保持轮次身份和截止时间；不会因此重置预算。 */
    public TurnContext forIteration(int nextIteration) {
        return new TurnContext(sessionId, turnId, agent, trigger, nextIteration, deadline);
    }
}
