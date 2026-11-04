package com.agentos.boot;

import com.agentos.cli.AgentOSCli;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * AgentOS 启动入口。
 *
 * <p>先交给 {@link AgentOSCli} 解析命令：{@code init}、{@code profile list} 这类只操作文件的命令不启动 Spring 上下文；
 * {@code chat}、{@code serve}、{@code gateway} 在各自的子命令里再启动 Spring（见技术方案 8.7）。
 */
@SpringBootApplication(scanBasePackages = "com.agentos")
public class AgentOSApplication {

    public static void main(String[] args) {
        System.exit(AgentOSCli.execute(args));
    }
}
