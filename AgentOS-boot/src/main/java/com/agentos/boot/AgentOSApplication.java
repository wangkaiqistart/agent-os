package com.agentos.boot;

import com.agentos.cli.AgentOSCli;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * AgentOS 启动入口。
 *
 * <p>这个类的职责很小：它只负责把 Java 进程交给命令行入口。
 * 不要把它理解成“这里已经启动了整个 Spring Boot 应用”。当前设计是先解析命令：
 * {@code init}、{@code profile list} 这类只操作文件的命令不需要启动 Spring 上下文；
 * {@code chat}、{@code serve}、{@code gateway} 以后会在各自的子命令中调用
 * {@code SpringApplication.run(...)} 启动 Spring。
 *
 * <p>{@link SpringBootApplication} 是 Spring Boot 的入口注解，概念上包含：
 * <ul>
 *   <li>把本类当作配置类；</li>
 *   <li>开启 Spring Boot 自动配置；</li>
 *   <li>扫描 {@code com.agentos} 包下的 Spring 组件。</li>
 * </ul>
 */
@SpringBootApplication(scanBasePackages = "com.agentos")
public class AgentOSApplication {

    public static void main(String[] args) {
        // System.exit 让命令执行结果成为进程退出码；例如 0 表示成功，非 0 表示失败。
        // 真正的命令解析在 AgentOSCli.execute 中完成。
        System.exit(AgentOSCli.execute(args));
    }
}
