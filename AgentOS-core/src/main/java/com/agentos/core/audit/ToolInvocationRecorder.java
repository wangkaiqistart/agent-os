package com.agentos.core.audit;

/** 工具审计生命周期端口：治理层只在 begin 确认提交后执行真实工具。 */
public interface ToolInvocationRecorder {
    /** 在产生工具副作用前登记执行意图，返回用于后续关联的记录。 */
    ToolInvocationRecord begin(ToolInvocationRecord record);

    /** 更新已经登记的工具执行结果，避免通过新增意图误触发同一次动作。 */
    void complete(ToolInvocationRecord record);

    /** 记录因预算、权限或其他执行条件而未实际运行的调用。 */
    void skipped(ToolInvocationRecord record);
}
