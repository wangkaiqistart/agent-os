package com.agentos.cli;

import com.agentos.core.turn.AgentService;

/** CLI 与 Spring 上下文之间的最小端口，测试可注入假实现。 */
@FunctionalInterface
public interface ContextLauncher {
    RunningContext launch();

    interface RunningContext extends AutoCloseable {
        AgentService agentService();

        @Override
        default void close() {}
    }
}
