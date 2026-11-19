package com.agentos.tool.mcp;

import com.agentos.core.tool.Redactor;
import com.agentos.core.tool.ToolExecutionScope;
import com.agentos.core.turn.ToolContextKeys;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongPredicate;
import java.util.regex.Pattern;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/** 单次调用使用原scope，取消只失效原代次，不改共享SDK超时、不重发。 */
public final class McpInvocationGuard implements ToolCallback {
    // 官方 SyncMcpToolCallback 把 isError=true 的结果包装成 "Error calling tool: " + content 列表的 toString。
    private static final String REMOTE_ERROR_PREFIX = "Error calling tool: ";
    private static final Pattern TEXT_CONTENT =
            Pattern.compile("TextContent\\[annotations=.*?, text=(.*?), meta=", Pattern.DOTALL);
    // 回给模型的远端错误正文上限；只用于让模型修正参数，不承载完整结果。
    private static final int MAX_REMOTE_ERROR_CHARS = 2000;

    private final ToolCallback delegate;
    private final McpClientGeneration generation;
    private final LongPredicate invalidate;
    private final Redactor redactor;

    public McpInvocationGuard(
            ToolCallback delegate,
            McpClientGeneration generation,
            LongPredicate invalidate,
            Redactor redactor) {
        this.delegate = delegate;
        this.generation = generation;
        this.invalidate = invalidate;
        this.redactor = redactor;
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
                                    String remote = remoteErrorText(failure);
                                    result.completeExceptionally(
                                            remote != null
                                                    ? new McpToolException(
                                                            "REMOTE_ERROR：远程工具报告执行失败，调用已结束："
                                                                    + remote)
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

    /**
     * 远端 isError 时返回脱敏、限长后的错误正文，供模型修正参数；其他失败返回 null。
     *
     * <p>优先取出 TextContent 的 text；格式不符时退回整段内容描述。正文是不可信数据，只进入工具结果，
     * 不写日志。
     */
    private String remoteErrorText(RuntimeException failure) {
        if (!(failure instanceof org.springframework.ai.tool.execution.ToolExecutionException)
                || !(failure.getCause() instanceof IllegalStateException remote)
                || remote.getMessage() == null
                || !remote.getMessage().startsWith(REMOTE_ERROR_PREFIX)) {
            return null;
        }
        String content = remote.getMessage().substring(REMOTE_ERROR_PREFIX.length());
        var texts = new StringBuilder();
        var matcher = TEXT_CONTENT.matcher(content);
        while (matcher.find()) {
            if (!texts.isEmpty()) {
                texts.append('\n');
            }
            texts.append(matcher.group(1));
        }
        String text = texts.isEmpty() ? content : texts.toString();
        String safe = redactor.summarize(text, MAX_REMOTE_ERROR_CHARS);
        return safe == null || safe.isBlank() ? "（远端未提供错误说明）" : safe;
    }
}
