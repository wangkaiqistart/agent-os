package com.agentos.tool.process;

/** 进程异常仅暴露安全分类，不附带程序、参数、环境或底层诊断。 */
public final class ShellToolException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ShellToolException(String category) {
        super(category);
    }
}
