package com.agentos.cli;

import java.nio.file.Path;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "init", description = "创建 AgentOS 工作区")
public final class InitCommand implements Runnable {
    @Option(names = "--workspace", defaultValue = ".AgentOS")
    Path workspace;

    @Override
    public void run() {
        try {
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
