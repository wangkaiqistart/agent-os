package com.agentos.boot;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

/**
 * 验证 application.yaml 的日志基线：文件日志是 ECS JSON，并带上 MDC 里的 sessionId / turnId。
 *
 * <p>只启动一个空的 Spring 上下文（不做自动配置），不连数据库、不需要模型 key。
 * 这是一种“隔离测试”：只测试日志，不让数据库或模型配置干扰结果。
 */
class LoggingBaselineTest {

    @TempDir Path tempDir;

    @Test
    @DisplayName("文件日志为 JSON，并带 MDC 关联字段")
    void fileLogIsStructuredJsonWithMdc() throws Exception {
        // JUnit 的 @TempDir 会创建临时目录，测试结束后自动清理。
        Path logFile = tempDir.resolve("agentos.log");
        try (ConfigurableApplicationContext context =
                new SpringApplicationBuilder(EmptyConfig.class)
                        .web(WebApplicationType.NONE)
                        .run("--logging.file.name=" + logFile, "--spring.config.import=")) {
            // 保留真实 application.yaml 的日志配置，但关闭其外部导入，离线测试不读取 .env。
            assertThat(
                            context.getEnvironment().getPropertySources().stream()
                                    .map(org.springframework.core.env.PropertySource::getName)
                                    .toList())
                    .noneMatch(name -> name.contains(".env"));
            // LoggerFactory 返回 SLF4J 日志门面，具体由 Spring Boot 的 Logback 实现负责落盘。
            Logger log = LoggerFactory.getLogger(LoggingBaselineTest.class);
            // MDC 是“当前线程的日志附加字段”，用于把日志关联到会话和轮次。
            MDC.put("sessionId", "s-1");
            MDC.put("turnId", "t-1");
            try {
                log.info("baseline-check");
            } finally {
                // 清理 MDC，避免同一线程后面的请求继承上一条请求的身份。
                MDC.clear();
            }
        }

        // 读取测试产生的日志，找出刚才写入的那一行，再断言它是 JSON 且带关联字段。
        String line =
                Files.readAllLines(logFile).stream()
                        .filter(l -> l.contains("baseline-check"))
                        .findFirst()
                        .orElseThrow();
        assertThat(line)
                .startsWith("{")
                .contains("\"sessionId\":\"s-1\"")
                .contains("\"turnId\":\"t-1\"");
    }

    @Configuration(proxyBeanMethods = false)
    static class EmptyConfig {}
}
