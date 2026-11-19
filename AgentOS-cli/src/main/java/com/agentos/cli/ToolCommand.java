package com.agentos.cli;

/** 工具目录子命令容器，不隐式启动聊天或服务。 */
@picocli.CommandLine.Command(
        name = "tool",
        mixinStandardHelpOptions = true,
        description = "查看工具目录或启动只读查询")
public final class ToolCommand implements Runnable {
    @picocli.CommandLine.Spec picocli.CommandLine.Model.CommandSpec spec;

    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }
}
