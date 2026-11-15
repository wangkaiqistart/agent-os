package com.agentos.core.turn;

public class TurnDeadlineExceededException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public TurnDeadlineExceededException() {
        super("轮次已超过截止时间");
    }
}
