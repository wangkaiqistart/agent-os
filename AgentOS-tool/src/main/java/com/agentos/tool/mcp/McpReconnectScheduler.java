package com.agentos.tool.mcp;

import java.time.Duration;

/** 仅调度未来连接，不重放业务调用。 */
public final class McpReconnectScheduler {
    private McpReconnectScheduler() {}

    public static Duration delay(int failures) {
        return Duration.ofSeconds(
                failures <= 1 ? 1 : failures >= 10 ? 300 : Math.min(300, 1L << (failures - 1)));
    }
}
