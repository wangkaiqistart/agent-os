package com.agentos.tool.mcp;

import com.agentos.core.tool.ToolExecutionScope;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import reactor.core.publisher.Mono;

/** 在官方自动分页聚合之前限制每页和总量；与initialize共享原始预算。 */
final class McpDiscoveryTransport implements McpClientTransport {
    private final McpClientTransport delegate;
    private final ToolExecutionScope scope;
    private final Set<Object> listRequests = ConcurrentHashMap.newKeySet();
    private volatile boolean discovering = true;
    private int tools;
    private int pages;

    McpDiscoveryTransport(McpClientTransport delegate, ToolExecutionScope scope) {
        this.delegate = delegate;
        this.scope = scope;
    }

    void discoveryComplete() {
        discovering = false;
    }

    public Mono<Void> connect(
            Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
        return delegate.connect(message -> handler.apply(message.map(this::inspect)));
    }

    private synchronized McpSchema.JSONRPCMessage inspect(McpSchema.JSONRPCMessage message) {
        if (discovering) {
            scope.checkpoint();
            if (message instanceof McpSchema.JSONRPCResponse response
                    && listRequests.remove(response.id())) {
                var page =
                        delegate.unmarshalFrom(
                                response.result(), new TypeRef<McpSchema.ListToolsResult>() {});
                tools += page.tools().size();
                if (tools > 1000 || ++pages > 1000) {
                    throw new McpToolException("INVALID_DEFINITION");
                }
            }
        }
        return message;
    }

    public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
        if (discovering) {
            scope.checkpoint();
            if (message instanceof McpSchema.JSONRPCRequest request
                    && request.method().equals("tools/list")) {
                listRequests.add(request.id());
            }
        }
        return delegate.sendMessage(message);
    }

    public void setExceptionHandler(Consumer<Throwable> handler) {
        delegate.setExceptionHandler(handler);
    }

    public Mono<Void> closeGracefully() {
        return delegate.closeGracefully();
    }

    public <T> T unmarshalFrom(Object value, TypeRef<T> type) {
        return delegate.unmarshalFrom(value, type);
    }
}
