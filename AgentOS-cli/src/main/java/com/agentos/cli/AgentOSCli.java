package com.agentos.cli;

import picocli.CommandLine;

/**
 * 命令行入口。
 *
 * <p>本模块可以单独运行（{@code java -jar AgentOS-cli/target/AgentOS-cli-exec.jar --version}），
 * {@code AgentOS-boot} 的启动类也委托给 {@link #execute(String...)}，两边共用同一套命令解析。
 */
public final class AgentOSCli {

    private AgentOSCli() {
    }

    public static void main(String[] args) {
        System.exit(execute(args));
    }

    /** 解析并执行命令，返回退出码，不调用 {@code System.exit}。 */
    public static int execute(String... args) {
        return new CommandLine(new AgentOSCommand()).execute(args);
    }
}
