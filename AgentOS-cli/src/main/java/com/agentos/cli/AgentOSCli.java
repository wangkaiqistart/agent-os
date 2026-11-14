package com.agentos.cli;

import picocli.CommandLine;

/**
 * 命令行入口。
 *
 * <p>本模块可以单独运行（{@code java -jar AgentOS-cli/target/AgentOS-cli-exec.jar --version}），
 * {@code AgentOS-boot} 的启动类也委托给 {@link #execute(String...)}，两边共用同一套命令解析。
 *
 * <p>这里使用 Picocli。Picocli 负责把命令行字符串，例如
 * {@code AgentOS --version}，解析成 Java 方法调用；本类不负责业务逻辑。
 */
public final class AgentOSCli {

    private AgentOSCli() {}

    public static void main(String[] args) {
        // 独立运行 CLI 模块时使用这一入口。
        System.exit(execute(args));
    }

    /** 解析并执行命令，返回退出码，不调用 {@code System.exit}。 */
    public static int execute(String... args) {
        // new AgentOSCommand() 是命令定义对象；CommandLine 是 Picocli 的执行器。
        // 返回 int 而不是在这里直接退出，方便启动模块和测试复用这段逻辑。
        return new CommandLine(new AgentOSCommand()).execute(args);
    }
}
