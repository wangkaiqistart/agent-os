package com.agentos.cli;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import picocli.CommandLine.IVersionProvider;

/**
 * 版本信息，供 {@code --version} 使用。
 *
 * <p>版本号来自构建时过滤生成的 {@code agentos-version.properties}，运行时信息来自当前 JVM。
 * 这里把“构建时信息”和“运行时信息”合并成多行文本交给 Picocli 展示。
 */
public class VersionInfo implements IVersionProvider {

    static final String RESOURCE = "/agentos-version.properties";

    @Override
    public String[] getVersion() {
        // Picocli 要求版本提供器返回字符串数组；数组中的每一项会显示为一行。
        Properties p = load();
        return new String[] {
            "AgentOS " + p.getProperty("agentos.version", "unknown"),
            "  构建时间    " + p.getProperty("agentos.build-time", "unknown"),
            "  Spring Boot " + p.getProperty("spring-boot.version", "unknown"),
            "  Spring AI   " + p.getProperty("spring-ai.version", "unknown"),
            "  Java        "
                    + System.getProperty("java.version")
                    + " ("
                    + System.getProperty("java.vendor")
                    + ")",
            "  系统        " + System.getProperty("os.name") + " " + System.getProperty("os.arch")
        };
    }

    private static Properties load() {
        Properties p = new Properties();
        try (InputStream in = VersionInfo.class.getResourceAsStream(RESOURCE)) {
            if (in != null) {
                // getResourceAsStream 从 classpath 读取打包进 JAR 的资源。
                p.load(in);
            }
        } catch (IOException e) {
            // 版本信息属于辅助信息；读取失败时显示 unknown，不影响命令本身运行。
        }
        return p;
    }
}
