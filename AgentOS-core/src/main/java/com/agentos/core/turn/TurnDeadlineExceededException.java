package com.agentos.core.turn;

/** 预算耗尽或外层等待被中断的统一信号，AgentService 将其转换为 TIMED_OUT。 */
public class TurnDeadlineExceededException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public TurnDeadlineExceededException() {
        super("轮次已超过截止时间");
    }
}
