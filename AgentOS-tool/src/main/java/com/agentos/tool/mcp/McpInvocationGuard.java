package com.agentos.tool.mcp;

import com.agentos.core.tool.ToolExecutionScope;
import com.agentos.core.turn.ToolContextKeys;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongPredicate;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/** 单次调用使用原scope，取消只失效原代次，不改共享SDK超时、不重发。 */
public final class McpInvocationGuard implements ToolCallback {
    private final ToolCallback delegate;
    private final McpClientGeneration generation;
    private final LongPredicate invalidate;

    public McpInvocationGuard(
            ToolCallback delegate, McpClientGeneration generation, LongPredicate invalidate) {
        this.delegate = delegate;
        this.generation = generation;
        this.invalidate = invalidate;
    }

    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    public String call(String input) {
        throw new McpToolException("UNAVAILABLE");
    }

    public String call(String input, ToolContext context) {
        if (!generation.available()) {
            throw new McpToolException("UNAVAILABLE");
        }
        if (!(context.getContext().get(ToolContextKeys.TOOL_SCOPE)
                instanceof ToolExecutionScope scope)) {
            throw new McpToolException("UNAVAILABLE");
        }
        scope.checkpoint();
        var registration = scope.register(invalidate::test);
        {
            if (!generation.available()) {
                throw new McpToolException("UNAVAILABLE");
            }
            scope.possiblyStarted();
            var result = new CompletableFuture<String>();
            var worker =
                    Thread.ofVirtual()
                            .name("agentos-mcp-call")
                            .start(() -> {
                                try {
                                    result.complete(
                                            delegate.call(input, new ToolContext(Map.of())));
                                } catch (RuntimeException failure) {
                                    result.completeExceptionally(
                                            new McpToolException("REMOTE_FAILED：远程动作可能已生效"));
                                }
                            });
            try {
                while (true) {
                    scope.checkpoint();
                    try {
                        String value =
                                result.get(
                                        Math.min(
                                                scope.remainingNanos(),
                                                TimeUnit.MILLISECONDS.toNanos(10)),
                                        TimeUnit.NANOSECONDS);
                        registration.close();
                        return value;
                    } catch (TimeoutException waiting) {
                        /* 等待仍使用原始预算。 */
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                worker.interrupt();
                throw new McpToolException("INTERRUPTED：远程动作可能已生效");
            } catch (ExecutionException failed) {
                worker.interrupt();
                throw new McpToolException("REMOTE_FAILED：远程动作可能已生效");
            } catch (RuntimeException failure) {
                worker.interrupt();
                throw failure;
            }
        }
    }
}
