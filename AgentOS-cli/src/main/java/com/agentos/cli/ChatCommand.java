package com.agentos.cli;

import com.agentos.core.agent.AgentLoader;
import com.agentos.core.error.AgentOSException;
import com.agentos.core.error.ErrorCode;
import com.agentos.core.session.SessionKey;
import com.agentos.core.turn.PersistenceStatus;
import com.agentos.core.turn.ProcessRequest;
import com.agentos.core.turn.TurnResult;
import com.agentos.core.turn.TurnStatus;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/** CLI 只负责选择、输入输出及退出码，所有消息仍交给同一个 AgentService。 */
@Command(name = "chat", mixinStandardHelpOptions = true, description = "与 Agent 对话")
public final class ChatCommand implements Callable<Integer> {
    @Option(names = "--profile")
    String profile;

    @Option(names = "--message")
    String message;

    // --new 先归档当前 Agent 的空闲会话，再由 startTurn 创建新 UUID 会话。
    @Option(names = "--new")
    boolean newSession;

    @Option(names = "--workspace", defaultValue = ".AgentOS")
    Path workspace;

    @Spec CommandSpec spec;
    private final ContextLauncher launcher;
    private final Path directory;
    private final BufferedReader input;
    private final CliIdentity identity;
    private final ProviderNameCatalog providers;

    public ChatCommand() {
        this(null);
    }

    public ChatCommand(ContextLauncher launcher) {
        this(
                launcher,
                Path.of(""),
                null,
                new SystemCliIdentity(),
                () -> java.util.Set.of("primary"));
    }

    ChatCommand(ContextLauncher launcher, Path directory, BufferedReader input) {
        this(
                launcher,
                directory,
                input,
                new SystemCliIdentity(),
                () -> java.util.Set.of("primary"));
    }

    ChatCommand(
            ContextLauncher launcher,
            Path directory,
            BufferedReader input,
            CliIdentity identity,
            ProviderNameCatalog providers) {
        this.launcher = launcher;
        this.directory = directory;
        this.input = input;
        this.identity = Objects.requireNonNull(identity, "identity");
        this.providers = Objects.requireNonNull(providers, "providers");
    }

    @Override
    public Integer call() {
        Path root = directory.resolve(workspace).toAbsolutePath().normalize();
        String selected;
        try {
            if (message != null && message.isBlank()) {
                throw new IllegalArgumentException("消息不能为空");
            }
            selected = selectProfile(root);
        } catch (AgentOSException | IllegalArgumentException e) {
            spec.commandLine().getErr().println(e.getMessage());
            return 2;
        } catch (IOException e) {
            spec.commandLine().getErr().println("无法读取 Agent 目录，请检查工作区路径与权限");
            return 2;
        }
        try {
            // chat 也必须先验证配置路径；无效配置不得启动 Spring。
            providers.names();
        } catch (IllegalArgumentException e) {
            spec.commandLine().getErr().println("配置不可用，请检查 --config 或本地 application.yaml");
            return 2;
        }
        if (launcher == null) {
            spec.commandLine().getErr().println("chat 需要完整应用的 Spring 上下文启动器");
            return 1;
        }
        ContextLauncher.RunningContext opened;
        try {
            opened = launcher.launch(root, providers.configurationPath().orElse(null));
        } catch (RuntimeException e) {
            spec.commandLine().getErr().println("聊天启动失败，请检查数据库和 Provider 配置");
            return 1;
        }
        // 整段对话共用一个上下文；成功、失败及 EOF 都会关闭，释放连接池。
        try (ContextLauncher.RunningContext context = opened) {
            if (newSession && !archiveCurrentSession(context, selected)) {
                return 3;
            }
            if (message != null) {
                return process(context, selected, message);
            }
            BufferedReader reader =
                    input == null
                            ? new BufferedReader(
                                    new InputStreamReader(System.in, StandardCharsets.UTF_8))
                            : input;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.equals("/exit")) {
                    break;
                }
                if (line.isBlank()) {
                    spec.commandLine().getErr().println("消息不能为空");
                    continue;
                }
                process(context, selected, line);
            }
            return 0;
        } catch (IOException | RuntimeException e) {
            spec.commandLine().getErr().println("对话中断，请检查运行配置后重试");
            return 3;
        }
    }

    private boolean archiveCurrentSession(ContextLauncher.RunningContext context, String selected) {
        if (context.sessionManager() == null) {
            spec.commandLine().getErr().println("无法创建新会话，请检查数据库和运行配置");
            return false;
        }
        try {
            context.sessionManager()
                    .archiveIfIdle(new SessionKey("cli", identity.userId(), selected));
            return true;
        } catch (AgentOSException e) {
            if (e.getErrorCode() == ErrorCode.SESSION_BUSY) {
                spec.commandLine().getErr().println("上一条消息还在处理");
            } else {
                spec.commandLine().getErr().println("无法创建新会话，请检查运行配置");
            }
            return false;
        } catch (RuntimeException e) {
            spec.commandLine().getErr().println("无法创建新会话，请检查运行配置");
            return false;
        }
    }

    /** 显式选择 > main > 旧工作区唯一 Agent；坏 main 不能被过滤后回退。 */
    private String selectProfile(Path root) throws IOException {
        Path agents = root.resolve("agents");
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                || !Files.isDirectory(agents, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("没有可用的 Agent，请先运行 init");
        }
        String selected = profile;
        if (selected == null) {
            if (Files.exists(agents.resolve("main"), LinkOption.NOFOLLOW_LINKS)) {
                selected = "main";
            } else {
                List<String> names;
                try (var paths = Files.list(agents)) {
                    names =
                            paths.filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
                                    .map(Path::getFileName)
                                    .filter(Objects::nonNull)
                                    .map(Path::toString)
                                    .sorted()
                                    .toList();
                }
                if (names.size() != 1) {
                    throw new IllegalArgumentException(
                            names.isEmpty()
                                    ? "没有可用的 Agent，请先运行 init"
                                    : "存在多个 Agent，请使用 --profile 指定：" + String.join(", ", names));
                }
                selected = names.getFirst();
            }
        }
        // 这里只做文件/字段校验，不校验其他未选 Agent，也不连接 Provider。
        new AgentLoader(agents).load(selected);
        return selected;
    }

    private int process(ContextLauncher.RunningContext context, String selected, String text) {
        try {
            TurnResult result =
                    context.agentService()
                            .process(
                                    new ProcessRequest(
                                            "cli", identity.userId(), selected, text, "cli"));
            if (result.persistenceStatus() == PersistenceStatus.UNCONFIRMED) {
                spec.commandLine()
                        .getErr()
                        .println("持久化未确认，turnId=" + result.turnId() + "；" + result.text());
                return 3;
            }
            if (result.status() == TurnStatus.SUCCEEDED) {
                spec.commandLine().getOut().println(result.text());
                return 0;
            }
            spec.commandLine().getErr().println(result.text());
            return 3;
        } catch (AgentOSException e) {
            if (e.getErrorCode() == ErrorCode.SESSION_BUSY) {
                spec.commandLine().getErr().println("上一条消息还在处理");
                return 3;
            }
            spec.commandLine().getErr().println("对话请求失败，请检查 Agent 和运行配置");
            return e.getErrorCode() == ErrorCode.INVALID_ARGUMENT ? 2 : 3;
        } catch (RuntimeException e) {
            // Provider/数据库异常可能含凭据和连接串，禁止转发异常原文或堆栈。
            spec.commandLine().getErr().println("本轮处理失败，请检查运行配置后重试");
            return 3;
        }
    }
}
