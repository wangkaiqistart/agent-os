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
        return execute(null, args);
    }

    /**
     * 由 boot 模块传入 Spring 上下文启动器；独立 CLI 保持无上下文行为，便于 init/help/version
     * 在没有数据库和模型配置时运行。
     */
    public static int execute(ContextLauncher launcher, String... args) {
        CommandLine commandLine = new CommandLine(new AgentOSCommand());
        // 动态注册可传入启动器；注解中的 init 则无需 Spring 依赖即可实例化。
        // 独立 CLI 的 launcher 为 null，执行 chat 会明确报错，完整 boot 入口才能启动对话链路。
        commandLine.addSubcommand("chat", new ChatCommand(launcher));
        return commandLine.execute(args);
    }
}
