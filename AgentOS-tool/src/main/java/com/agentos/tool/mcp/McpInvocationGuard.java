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
                                            remoteReportedError(failure)
                                                    ? new McpToolException(
                                                            "REMOTE_ERROR：远程工具报告执行失败，调用已结束；"
                                                                    + "远端错误内容不回显")
                                                    : new McpToolException(
                                                            "REMOTE_FAILED：远程动作可能已生效"));
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
                // 委托已经返回（远端报错或请求级失败），不再有在途调用，不能借 scope 关闭拆掉整条连接；
                // 真正的断连由 transport 异常处理器单独失效该代次。
                registration.close();
                throw failed.getCause() instanceof McpToolException known
                        ? known
                        : new McpToolException("REMOTE_FAILED：远程动作可能已生效");
            } catch (RuntimeException failure) {
                worker.interrupt();
                throw failure;
            }
        }
    }

    /** 官方 SyncMcpToolCallback 把 isError=true 的结果包装成带固定前缀的 IllegalStateException。 */
    private static boolean remoteReportedError(RuntimeException failure) {
        return failure instanceof org.springframework.ai.tool.execution.ToolExecutionException
                && failure.getCause() instanceof IllegalStateException remote
                && remote.getMessage() != null
                && remote.getMessage().startsWith("Error calling tool: ");
    }
}
