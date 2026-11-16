package com.agentos.cli;

import com.agentos.core.turn.ProcessRequest;
import com.agentos.core.turn.TurnResult;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * chat 子命令适配器：选择 Agent、按需启动 Spring，再把终端输入转换为 ProcessRequest。
 *
 * <p>由 Picocli 创建/解析参数，不是 Spring Controller。通过 ContextLauncher 获取应用服务，
 * CLI 模块不直接依赖 boot 模块。当前在本类手动读标准输入，尚未复用 CliChannel。
 */
@Command(name = "chat", description = "与 Agent 对话")
public final class ChatCommand implements Runnable {
    // 对应 agents/<名称>/AGENT.md 中的 Agent 名称，不是 Spring 的 active profile。
    @Option(names = "--profile")
    String profile;

    // 提供时只问一次；不提供时持续读取标准输入，直到 /exit 或 EOF。
    @Option(names = "--message")
    String message;

    // 目前仅保留命令行契约，run 尚未使用此字段，新会话语义还未实现。
    @Option(names = "--new")
    boolean newSession;

    // 当前只影响候选 Agent 目录扫描，不会传给 Spring 的 agentos.workspace.root。
    // 使用自定义目录时，两边必须单独保持一致，否则可能“选得到、加载不到”。
    @Option(names = "--workspace", defaultValue = ".AgentOS")
    Path workspace;

    private ContextLauncher launcher;

    public ChatCommand() {}

    public ChatCommand(ContextLauncher launcher) {
        this.launcher = launcher;
    }

    public void launcher(ContextLauncher launcher) {
        this.launcher = launcher;
    }

    @Override
    public void run() {
        try {
            // 先解决 Agent 选择，再启动较重的容器/数据库；help 和 init 不走这里。
            String selected = selectProfile();
            if (launcher == null) {
                throw new IllegalStateException("chat 需要 Spring 上下文");
            }
            // 整段交互共用一个 Spring 上下文；退出或异常都会 close，释放连接池等资源。
            try (ContextLauncher.RunningContext context = launcher.launch()) {
                if (message != null) {
                    print(
                            context.agentService()
                                    .process(
                                            new ProcessRequest(
                                                    "cli",
                                                    System.getProperty("user.name", "unknown"),
                                                    selected,
                                                    message,
                                                    "cli")));
                    return;
                }
                // 每次 process 阻塞等待一轮完成，所以这里是串行交互，不会同时发送多轮。
                // user.name 仅用于本机 CLI 会话区分，不是经过身份验证的账号。
                BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.equals("/exit")) {
                        break;
                    }
                    if (line.isBlank()) {
                        continue;
                    }
                    print(
                            context.agentService()
                                    .process(
                                            new ProcessRequest(
                                                    "cli",
                                                    System.getProperty("user.name", "unknown"),
                                                    selected,
                                                    line,
                                                    "cli")));
                }
            }
        } catch (Exception e) {
            // 统一交给 Picocli 的命令执行错误流程，避免把框架异常处理带进 core。
            throw new picocli.CommandLine.PicocliException(e.getMessage(), e);
        }
    }

    /** 指定名称时直接使用；未指定时仅支持恰有一个目录，不会弹出交互选择器。 */
    private String selectProfile() throws Exception {
        Path agents = workspace.resolve("agents");
        if (profile != null && !profile.isBlank()) {
            return profile;
        }
        if (!Files.isDirectory(agents)) {
            throw new IllegalArgumentException("没有 Agent，请先运行 init");
        }
        List<String> names =
                // 此处只列目录；AGENT.md 是否存在、名称/模型是否合法留给 AgentLoader 校验。
                Files.list(agents)
                        .filter(Files::isDirectory)
                        .map(p -> {
                            Path fileName = p.getFileName();
                            return fileName == null ? "" : fileName.toString();
                        })
                        .sorted(Comparator.naturalOrder())
                        .toList();
        if (names.size() != 1) {
            throw new IllegalArgumentException(
                    names.isEmpty() ? "没有可用的 Agent" : "存在多个 Agent，请使用 --profile");
        }
        return names.getFirst();
    }

    /** 当前终端只显示结果文本；业务状态和持久化状态仍在 TurnResult 中。 */
    private static void print(TurnResult result) {
        System.out.println(result.text());
    }
}
