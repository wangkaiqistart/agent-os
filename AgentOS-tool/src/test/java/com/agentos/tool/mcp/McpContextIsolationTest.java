package com.agentos.tool.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.tool.registry.ToolRegistry;
import com.agentos.tool.support.LocalMcpServers;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpContextIsolationTest {
    @Test
    void arbitraryInternalContextIsNeverSentAndUntrustedErrorsStaySafe() throws Exception {
        var appender =
                new ch.qos.logback.core.read.ListAppender<
                        ch.qos.logback.classic.spi.ILoggingEvent>();
        var rootLogger =
                (ch.qos.logback.classic.Logger)
                        org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        appender.start();
        rootLogger.addAppender(appender);
        try (var fixture = new LocalMcpServers("streamable-http")) {
            var registry = new ToolRegistry(List.of());
            try (var service =
                    McpTestSupport.service(
                            List.of(fixture.configuration("privacy")),
                            fixture.directory(),
                            registry)) {
                service.start();
                McpTestSupport.available(service, "privacy");
                var callback =
                        registry.resolve(
                                        McpTestSupport.profile(
                                                List.of("mcp_privacy__echo"), List.of("privacy")))
                                .executionCallbacks()
                                .getFirst();
                assertThat(
                                callback.call(
                                        "{\"value\":\"business-sentinel\"}",
                                        McpTestSupport.context(
                                                "mcp_privacy__echo", Duration.ofSeconds(2))))
                        .contains("business-sentinel");
                var observed = fixture.stats().path("calls").get(0);
                assertThat(observed.toString())
                        .contains("business-sentinel")
                        .doesNotContain(
                                "private-context-sentinel",
                                "UNKNOWN_INTERNAL_SENTINEL",
                                "turnId",
                                "workspace");
                assertThat(observed.path("params").path("_meta").size()).isZero();
                fixture.control(
                        Map.of(
                                "isError",
                                true,
                                "errorText",
                                "REMOTE_PRIVATE_SENTINEL https://secret.example/?token=private"));
                // 远端错误说明回给模型以便修正参数，但先经脱敏：URL 中的令牌不外泄，日志也不记录正文。
                assertThatThrownBy(() ->
                                callback.call(
                                        "{}",
                                        McpTestSupport.context(
                                                "mcp_privacy__echo", Duration.ofSeconds(2))))
                        .hasMessageContaining("REMOTE_ERROR")
                        .hasMessageContaining("REMOTE_PRIVATE_SENTINEL")
                        .hasMessageNotContaining("token=private")
                        .hasMessageNotContaining("TextContent[");
            }
        } finally {
            rootLogger.detachAppender(appender);
            appender.stop();
        }
        assertThat(appender.list)
                .allSatisfy(event -> {
                    assertThat(event.getFormattedMessage())
                            .doesNotContain(
                                    "REMOTE_PRIVATE_SENTINEL",
                                    "secret.example",
                                    "private-context-sentinel");
                    if (event.getThrowableProxy() != null) {
                        assertThat(event.getThrowableProxy().getMessage())
                                .doesNotContain("REMOTE_PRIVATE_SENTINEL", "secret.example");
                    }
                });
    }
}
