package com.agentos.core.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 运行工作区的根目录，由 CoreAutoConfiguration 启用绑定。
 *
 * <p>相对路径按 Java 进程工作目录解析，默认 .AgentOS。它不改变日志文件等独立配置的路径，
 * 也不会自动创建目录；缺失文件应通过 CLI init 初始化。
 *
 * @param root 存放 agents、共享 Bootstrap、memory 等运行文件的目录
 */
@ConfigurationProperties("agentos.workspace")
public record WorkspaceProperties(Path root) {
    public WorkspaceProperties {
        root = root == null ? Path.of(".AgentOS") : root;
    }
}
