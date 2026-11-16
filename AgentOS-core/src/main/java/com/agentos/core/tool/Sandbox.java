package com.agentos.core.tool;

/**
 * 工具在产生外部副作用之前调用的授权检查端口；当前仅定义契约，尚无正式工具执行接入。
 * 它表达白名单等业务规则，不是 JDK SecurityManager 或容器级系统隔离。
 */
public interface Sandbox {
    /** 允许时正常返回；拒绝时抛 SandboxViolationException，不能先执行动作再检查。 */
    void enforce(SandboxAction action);
}
