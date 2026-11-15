package com.agentos.boot;

import com.agentos.cli.ContextLauncher;
import com.agentos.core.turn.AgentService;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** chat 模式使用的无 Web Spring 上下文启动器。 */
public final class SpringContextLauncher implements ContextLauncher {
    @Override
    public RunningContext launch() {
        ConfigurableApplicationContext context =
                new SpringApplicationBuilder(AgentOSApplication.class)
                        .web(WebApplicationType.NONE)
                        .run();
        return new RunningContext() {
            @Override
            public AgentService agentService() {
                return context.getBean(AgentService.class);
            }

            @Override
            public void close() {
                context.close();
            }
        };
    }
}
