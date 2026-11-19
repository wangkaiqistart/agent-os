package com.agentos.tool.mcp;

import com.agentos.core.tool.ToolDescriptor;
import com.agentos.core.tool.ToolSourceDescriptor;
import java.time.Instant;
import java.util.List;

/** 服务内部状态，只在McpClientService监视器下维护，禁止序列化配置及客户端。 */
final class ToolSourceState {
    final McpServerConfig config;
    McpClientGeneration generation = new McpClientGeneration(0);
    ToolSourceDescriptor.State state = ToolSourceDescriptor.State.CONFIGURED;
    ToolSourceDescriptor.Reason reason;
    List<ToolDescriptor> known = List.of();
    int failures;
    Instant nextReconnectAt;
    long initDeadline;

    ToolSourceState(McpServerConfig config) {
        this.config = config;
    }
}
