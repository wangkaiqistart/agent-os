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

@Command(name = "chat", description = "与 Agent 对话")
public final class ChatCommand implements Runnable {
    @Option(names = "--profile")
    String profile;

    @Option(names = "--message")
    String message;

    @Option(names = "--new")
    boolean newSession;

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
            String selected = selectProfile();
            if (launcher == null) {
                throw new IllegalStateException("chat 需要 Spring 上下文");
            }
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
            throw new picocli.CommandLine.PicocliException(e.getMessage(), e);
        }
    }

    private String selectProfile() throws Exception {
        Path agents = workspace.resolve("agents");
        if (profile != null && !profile.isBlank()) {
            return profile;
        }
        if (!Files.isDirectory(agents)) {
            throw new IllegalArgumentException("没有 Agent，请先运行 init");
        }
        List<String> names =
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

    private static void print(TurnResult result) {
        System.out.println(result.text());
    }
}
