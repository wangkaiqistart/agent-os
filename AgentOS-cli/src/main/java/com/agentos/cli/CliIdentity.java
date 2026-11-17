package com.agentos.cli;

/** CLI 会话使用者标识的注入端口。v1 只允许由运行环境提供，命令行不提供覆盖参数。 */
@FunctionalInterface
public interface CliIdentity {
    /** 返回当前 CLI 使用者的稳定、非空标识。 */
    String userId();
}
