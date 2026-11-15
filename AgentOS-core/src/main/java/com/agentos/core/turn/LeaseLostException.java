package com.agentos.core.turn;

public class LeaseLostException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public LeaseLostException() {
        super("会话租约已失效");
    }
}
