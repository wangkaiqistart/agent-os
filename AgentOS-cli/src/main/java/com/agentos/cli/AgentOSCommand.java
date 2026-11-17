package com.agentos.cli;

import java.nio.file.Path;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Picocli 根命令。
 *
 * <p>核心阶段的 12 个子命令（init、status、chat、serve、gateway、profile list/create/show/delete、
 * provider list、tool list、session list）在实现各 user story 时逐个注册到 {@code subcommands}。
 *
 * <p>当前 init/chat 由 AgentOSCli 注入文件输入与 ContextLauncher 后动态注册；
 * 其余目标子命令尚未实现。不带子命令时显示帮助，每个子命令负责把参数转换成应用服务调用。
 */
@Command(
        name = "AgentOS",
        mixinStandardHelpOptions = true,
        versionProvider = VersionInfo.class,
        description = "Java 原生、私有部署、可审计的企业级 Agent OS")
public class AgentOSCommand implements Runnable {

    private final ConfigPathRef configPath;

    public AgentOSCommand() {
        this(new ConfigPathRef());
    }

    AgentOSCommand(ConfigPathRef configPath) {
        this.configPath = configPath;
    }

    @Option(names = "--config", description = "CLI 与 Boot 共用的 application.yaml 路径")
    void setConfig(Path value) {
        configPath.set(value);
    }

    // Picocli 在执行命令前自动注入当前命令的描述对象。
    // 它不是 Spring 的依赖注入，而是 Picocli 自己的参数注入。
    @Spec CommandSpec spec;

    /** 不带子命令时打印帮助。 */
    @Override
    public void run() {
        // usage 会根据 @Command 注解自动生成命令名、--help、--version 等帮助文本。
        spec.commandLine().usage(System.out);
    }
}
