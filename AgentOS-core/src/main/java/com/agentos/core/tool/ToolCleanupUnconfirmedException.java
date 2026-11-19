package com.agentos.core.tool;

/** 资源仍可能在执行；必须终止本轮，不能转成普通工具结果后继续动作或重放。 */
public final class ToolCleanupUnconfirmedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ToolCleanupUnconfirmedException() {
        super("操作状态不确定；资源清理未确认；本轮已停止，禁止重放");
    }
}
