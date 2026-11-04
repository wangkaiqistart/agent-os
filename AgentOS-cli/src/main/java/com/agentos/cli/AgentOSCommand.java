package com.agentos.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * Picocli 根命令。
 *
 * <p>核心阶段的 12 个子命令（init、status、chat、serve、gateway、profile list/create/show/delete、
 * provider list、tool list、session list）在实现各 user story 时逐个注册到 {@code subcommands}。
 */
@Command(
        name = "AgentOS",
        mixinStandardHelpOptions = true,
        versionProvider = VersionInfo.class,
        description = "Java 原生、私有部署、可审计的企业级 Agent OS")
public class AgentOSCommand implements Runnable {

    @Spec
    CommandSpec spec;

    /** 不带子命令时打印帮助。 */
    @Override
    public void run() {
        spec.commandLine().usage(System.out);
    }
}
