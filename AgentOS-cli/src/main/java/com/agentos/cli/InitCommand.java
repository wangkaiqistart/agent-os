package com.agentos.cli;

import java.nio.file.Path;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * 只操作本地文件的 init 命令，不启动 Spring，也不连接数据库或模型。
 * workspace 由 Picocli 的 Option 注入，和 Spring 的 ConfigurationProperties 是两套机制。
 */
@Command(name = "init", description = "创建 AgentOS 工作区")
public final class InitCommand implements Runnable {
    @Option(names = "--workspace", defaultValue = ".AgentOS")
    Path workspace;

    @Override
    public void run() {
        try {
            // 初始化器返回统计信息，由入口决定如何显示；已有文件跳过，保留用户手工配置。
            WorkspaceInitializer.Result result = new WorkspaceInitializer().initialize(workspace);
            System.out.println(
                    "工作区已准备：新建 "
                            + result.created().size()
                            + " 项，跳过 "
                            + result.skipped().size()
                            + " 项");
        } catch (Exception e) {
            throw new picocli.CommandLine.PicocliException("初始化失败：" + e.getMessage(), e);
        }
    }
}
