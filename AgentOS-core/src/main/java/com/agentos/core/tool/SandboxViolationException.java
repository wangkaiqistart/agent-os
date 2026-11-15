package com.agentos.core.tool;

public class SandboxViolationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public SandboxViolationException(String message) {
        super(message);
    }
}
