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
 */
class LoggingBaselineTest {

    @TempDir Path tempDir;

    @Test
    @DisplayName("文件日志为 JSON，并带 MDC 关联字段")
    void fileLogIsStructuredJsonWithMdc() throws Exception {
        Path logFile = tempDir.resolve("agentos.log");
        try (ConfigurableApplicationContext ignored =
                new SpringApplicationBuilder(EmptyConfig.class)
                        .web(WebApplicationType.NONE)
                        .run("--logging.file.name=" + logFile)) {
            Logger log = LoggerFactory.getLogger(LoggingBaselineTest.class);
            MDC.put("sessionId", "s-1");
            MDC.put("turnId", "t-1");
            try {
                log.info("baseline-check");
            } finally {
                MDC.clear();
            }
        }

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
