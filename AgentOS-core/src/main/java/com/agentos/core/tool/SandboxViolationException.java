package com.agentos.core.tool;

/** 沙箱拒绝动作的业务信号；后续工具错误适配可据此反馈模型，当前尚未接入该流程。 */
public class SandboxViolationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public SandboxViolationException(String message) {
        super(message);
    }
}
