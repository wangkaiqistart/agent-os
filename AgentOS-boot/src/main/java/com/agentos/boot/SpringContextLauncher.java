package com.agentos.boot;

import com.agentos.cli.ContextLauncher;
import com.agentos.core.turn.AgentService;
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
    @Override
    public RunningContext launch() {
        // 每次 chat 命令启动一个容器；ChatCommand 在交互期间复用它，退出时 close。
        ConfigurableApplicationContext context =
                new SpringApplicationBuilder(AgentOSApplication.class)
                        .web(WebApplicationType.NONE)
                        .run();
        return new RunningContext() {
            @Override
            public AgentService agentService() {
                // 从容器取得已装配服务，避免手动 new 绕过依赖注入与存储事务代理。
                return context.getBean(AgentService.class);
            }

            @Override
            public void close() {
                // 关闭容器同时触发 Bean 销毁，释放连接池和后台校验器等资源。
                context.close();
            }
        };
    }
}
