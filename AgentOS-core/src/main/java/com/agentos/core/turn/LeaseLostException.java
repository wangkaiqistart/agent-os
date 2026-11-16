package com.agentos.core.turn;

/** 检查点续租失败：本轮已无权继续处理会话，由 AgentService 按运行失败收尾。 */
public class LeaseLostException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public LeaseLostException() {
        super("会话租约已失效");
    }
}
