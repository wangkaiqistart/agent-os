package com.agentos.core.tool;

/** 沙箱拒绝动作的业务信号；治理层记录 sandbox_denied 并把安全原因反馈模型。 */
public class SandboxViolationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public SandboxViolationException(String message) {
        super(message);
    }
}
