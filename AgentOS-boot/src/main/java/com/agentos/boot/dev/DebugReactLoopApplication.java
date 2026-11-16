package com.agentos.boot.dev;

import com.agentos.boot.AgentOSApplication;
import com.agentos.web.dev.AgentLoopDebugController;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * 临时启动完整 AgentOS Web 上下文，用于断点调试 AgentService 到 ReActLoop 的链路。
 *
 * <p>调试结束后可与 {@link AgentLoopDebugController} 一起删除。它不替换正式
 * {@link AgentOSApplication} 入口，也不改变正式 CLI 的 WebApplicationType.NONE 行为。
 *
 * <p>IDE 工作目录应设为仓库根目录，便于解析 .AgentOS 与可选 .env 的相对路径。
 * 这是完整应用启动，会按配置建立数据源、运行迁移，并在请求时真实调用所选模型。
 */
public final class DebugReactLoopApplication {
    private DebugReactLoopApplication() {}

    public static void main(String[] args) {
        // 强制启用 Servlet 容器，供 Controller 接收请求；不用正式 main 的命令行分发流程。
        // properties 给调试开关设置默认值，更高优先级的命令行参数仍可覆盖；args 原样交给 Boot。
        new SpringApplicationBuilder(AgentOSApplication.class)
                .web(WebApplicationType.SERVLET)
                .properties("agentos.debug.react-loop.enabled=true")
                .run(args);
    }
}
