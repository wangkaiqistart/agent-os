package com.agentos.tool.mcp;

import com.agentos.core.tool.Redactor;
import com.agentos.core.tool.ToolDescriptor;
import io.modelcontextprotocol.client.McpSyncClient;
import java.util.Arrays;
import java.util.List;
import java.util.function.LongPredicate;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.mcp.ToolContextToMcpMetaConverter;

/** 官方provider生成回调；只增加稳定公开名和来源代次守卫。 */
public final class McpToolRegistration {
    private McpToolRegistration() {}

    public static List<ToolDescriptor> discover(
            McpServerConfig config,
            McpSyncClient client,
            McpClientGeneration generation,
            LongPredicate invalidate,
            Redactor redactor) {
        var callbacks =
                SyncMcpToolCallbackProvider.builder()
                        .mcpClients(client)
                        .toolNamePrefixGenerator(
                                (connection, tool) -> "mcp_" + config.name() + "__" + tool.name())
                        .toolContextToMcpMetaConverter(ToolContextToMcpMetaConverter.noOp())
                        .build()
                        .getToolCallbacks();
        return Arrays.stream(callbacks)
                .map(callback -> {
                    var definition = callback.getToolDefinition();
                    String prefix = "mcp_" + config.name() + "__";
                    String remote = definition.name().substring(prefix.length());
                    if (!remote.matches("[A-Za-z0-9_-]+") || definition.name().length() > 64) {
                        throw new McpToolException("INVALID_DEFINITION");
                    }
                    var guarded =
                            new McpInvocationGuard(callback, generation, invalidate, redactor);
                    String description = redactor.summarize(definition.description(), 4000);
                    if (description == null || description.isBlank()) {
                        description = "外部MCP工具";
                    }
                    return new ToolDescriptor(
                            definition.name(),
                            description,
                            definition.inputSchema(),
                            "mcp:" + config.name(),
                            remote,
                            config.tools().getOrDefault(remote, config.policy()),
                            callback.getToolMetadata(),
                            guarded,
                            callback,
                            ToolDescriptor.Availability.AVAILABLE,
                            generation.id());
                })
                .toList();
    }
}
