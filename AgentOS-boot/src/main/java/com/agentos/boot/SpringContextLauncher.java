package com.agentos.boot;

import com.agentos.cli.ContextLauncher;
import com.agentos.core.session.SessionManager;
import com.agentos.core.turn.AgentService;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * chat 模式使用的无 Web Spring 上下文启动器，是 CLI 端口在 boot 模块的实现。
 *
 * <p>AgentOSApplication 在这里作为配置源交给 Spring，不会再次调用它的 main，
 * 因而不会递归执行 Picocli。NONE 模式保留 Bean/数据源/事务等能力，但不监听 HTTP 端口。
 */
public final class SpringContextLauncher implements ContextLauncher {
    private final Supplier<SpringApplicationBuilder> builders;

    public SpringContextLauncher() {
        this(() -> new SpringApplicationBuilder(AgentOSApplication.class));
    }

    /** 包内测试入口只替换构建工厂，生产仍由本类强制 NONE 模式及工作区参数。 */
    SpringContextLauncher(Supplier<SpringApplicationBuilder> builders) {
        this.builders = Objects.requireNonNull(builders, "builders");
    }

    @Override
    public CatalogContext launchCatalog(Path workspaceRoot, Path configPath, boolean serve) {
        return new ToolCatalogContextLauncher().launch(workspaceRoot, configPath, serve);
    }

    @Override
    public RunningContext launch(Path workspaceRoot) {
        return launch(workspaceRoot, null);
    }

    @Override
    public RunningContext launch(Path workspaceRoot, Path configPath) {
        Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        if (!workspaceRoot.isAbsolute() || !workspaceRoot.equals(workspaceRoot.normalize())) {
            throw new IllegalArgumentException("workspaceRoot 必须是规范化绝对路径");
        }
        // 每次 chat 命令启动一个容器；ChatCommand 在交互期间复用它，退出时 close。
        if (configPath != null
                && (!configPath.isAbsolute() || !configPath.equals(configPath.normalize()))) {
            throw new IllegalArgumentException("configPath 必须是规范化绝对路径");
        }
        java.util.List<String> arguments =
                new java.util.ArrayList<>(
                        java.util.List.of(
                                "--agentos.workspace.root=" + workspaceRoot,
                                "--agentos.runtime.chat=true"));
        if (configPath != null) {
            arguments.add("--spring.config.additional-location=" + configPath.toUri());
        }
        ConfigurableApplicationContext context =
                builders.get().web(WebApplicationType.NONE).run(arguments.toArray(String[]::new));
        return new RunningContext() {
            @Override
            public AgentService agentService() {
                // 从容器取得已装配服务，避免手动 new 绕过依赖注入与存储事务代理。
                return context.getBean(AgentService.class);
            }

            @Override
            public SessionManager sessionManager() {
                return context.getBean(SessionManager.class);
            }

            @Override
            public void close() {
                // 关闭容器同时触发 Bean 销毁，释放连接池和后台校验器等资源。
                context.close();
            }
        };
    }
}
