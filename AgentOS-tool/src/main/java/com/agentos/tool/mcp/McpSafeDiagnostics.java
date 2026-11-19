package com.agentos.tool.mcp;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import org.slf4j.LoggerFactory;

/** 官方内容日志可包含原协议和异常正文，必须在初始化任何来源之前关闭。 */
public final class McpSafeDiagnostics {
    private McpSafeDiagnostics() {}

    private static boolean containsMcpFailure(Throwable failure) {
        for (int depth = 0; failure != null && depth < 16; depth++, failure = failure.getCause()) {
            if (failure.getClass().getName().startsWith("io.modelcontextprotocol.")
                    || failure instanceof McpToolException) {
                return true;
            }
        }
        return false;
    }

    private static final java.util.concurrent.atomic.AtomicBoolean CONFIGURED =
            new java.util.concurrent.atomic.AtomicBoolean();

    public static void configure() {
        if (!CONFIGURED.compareAndSet(false, true)) {
            return;
        }
        if (LoggerFactory.getILoggerFactory() instanceof LoggerContext context) {
            context.getLogger("io.modelcontextprotocol").setLevel(Level.OFF);
            context.getLogger("org.springframework.ai.mcp").setLevel(Level.OFF);
            context.addTurboFilter(
                    new ch.qos.logback.classic.turbo.TurboFilter() {
                        @Override
                        public ch.qos.logback.core.spi.FilterReply decide(
                                org.slf4j.Marker marker,
                                ch.qos.logback.classic.Logger logger,
                                Level level,
                                String format,
                                Object[] params,
                                Throwable failure) {
                            if (logger.getName().startsWith("reactor.")
                                    && containsMcpFailure(failure)) {
                                return ch.qos.logback.core.spi.FilterReply.DENY;
                            }
                            return ch.qos.logback.core.spi.FilterReply.NEUTRAL;
                        }
                    });
        }
    }
}
