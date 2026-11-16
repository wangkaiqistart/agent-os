package com.agentos.cli;

import com.agentos.core.turn.AgentService;
import java.nio.file.Path;

/**
 * CLI 与 Spring 上下文之间的最小端口，测试可注入假实现而无需连接数据库。
 *
 * <p>接口放在 CLI，boot 提供实现，从而避免 CLI 反向依赖启动模块。
 * 创建命令树时只传启动器，真正执行 chat 才调用 launch，实现延迟启动。
 */
@FunctionalInterface
public interface ContextLauncher {
    /** 接收规范化绝对工作区；启动一次上下文，由命令通过 try-with-resources 关闭。 */
    RunningContext launch(Path workspaceRoot);

    /** 只暴露业务入口和关闭能力，CLI 无需持有 Spring 的 ApplicationContext 类型。 */
    interface RunningContext extends AutoCloseable {
        AgentService agentService();

        // 假实现可以不持有资源；boot 的真实实现覆盖它并关闭 Spring 容器。
        @Override
        default void close() {}
    }
}
