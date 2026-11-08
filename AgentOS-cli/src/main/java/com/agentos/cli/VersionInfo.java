package com.agentos.cli;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import picocli.CommandLine.IVersionProvider;

/**
 * 版本信息，供 {@code --version} 使用。
 *
 * <p>版本号来自构建时过滤生成的 {@code agentos-version.properties}，运行时信息来自当前 JVM。
 */
public class VersionInfo implements IVersionProvider {

    static final String RESOURCE = "/agentos-version.properties";

    @Override
    public String[] getVersion() {
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
                p.load(in);
            }
        } catch (IOException e) {
            // 读不到资源时各项显示 unknown，不影响命令运行
        }
        return p;
    }
}
