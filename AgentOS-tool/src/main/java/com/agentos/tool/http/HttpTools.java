package com.agentos.tool.http;

import com.agentos.core.tool.ActionType;
import com.agentos.core.tool.Sandbox;
import com.agentos.core.tool.SandboxAction;
import com.agentos.core.tool.SandboxViolationException;
import com.agentos.core.turn.ToolCallLedger;
import com.agentos.core.turn.ToolContextKeys;
import com.agentos.core.turn.TurnDeadlineExceededException;
import com.agentos.core.turn.TurnExecutionBudget;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/** 只读 HTTP 工具。每次调用只有一个总截止时间，跳转和分片均不能重新开始计时。 */
public final class HttpTools implements AutoCloseable {
    private final HttpToolProperties properties;
    private final Sandbox sandbox;
    private final HttpClient client;

    public HttpTools(HttpToolProperties properties, Sandbox sandbox) {
        this.properties = properties;
        this.sandbox = sandbox;
        client =
                HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .connectTimeout(properties.timeout())
                        .build();
    }

    @Tool(name = "http_get", description = "读取已授权 HTTP/HTTPS 地址，返回响应正文")
    public String http_get(
            @ToolParam(description = "完整 HTTP 或 HTTPS URL") String url, ToolContext context) {
        var budget = (TurnExecutionBudget) context.getContext().get(ToolContextKeys.TURN_BUDGET);
        long duration = Math.min(properties.timeout().toNanos(), budget.remainingNanos());
        long deadline = System.nanoTime() + duration;
        URI uri;
        try {
            uri = URI.create(url);
        } catch (RuntimeException e) {
            throw new SandboxViolationException("无效 HTTP 地址");
        }
        for (int redirects = 0; ; redirects++) {
            var ledger =
                    (ToolCallLedger) context.getContext().get(ToolContextKeys.TOOL_CALL_LEDGER);
            if (ledger != null) {
                ledger.checkpoint();
            }
            if (budget.expired() || budget.cancelled()) {
                throw new TurnDeadlineExceededException();
            }
            sandbox.enforce(new SandboxAction(ActionType.HTTP, uri.toString()));
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new IllegalStateException("HTTP 请求超时");
            }
            var request =
                    HttpRequest.newBuilder(uri)
                            .GET()
                            .timeout(Duration.ofNanos(remaining))
                            .header("User-Agent", properties.userAgent())
                            .build();
            var future =
                    client.sendAsync(
                            request,
                            info -> new BoundedBody(properties.maxResponseBytes(), deadline));
            HttpResponse<byte[]> response;
            try {
                response =
                        future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                if (budget.expired() || budget.cancelled()) {
                    throw new TurnDeadlineExceededException();
                }
                throw new IllegalStateException("HTTP 请求中断");
            } catch (TimeoutException | ExecutionException e) {
                future.cancel(true);
                if (budget.expired() || budget.cancelled()) {
                    throw new TurnDeadlineExceededException();
                }
                // 只识别本地固定类型，不附带可能含完整 URL 或响应正文的 SDK cause。
                for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                    if (cause instanceof ResponseTooLargeException) {
                        throw new IllegalStateException("HTTP 响应正文超过大小上限");
                    }
                    if (cause instanceof java.net.http.HttpTimeoutException
                            || cause instanceof TimeoutException) {
                        throw new IllegalStateException("HTTP 请求超时");
                    }
                }
                throw new IllegalStateException("HTTP 请求失败");
            }
            if (budget.expired() || budget.cancelled()) {
                throw new TurnDeadlineExceededException();
            }
            if (response.statusCode() >= 300 && response.statusCode() < 400) {
                if (redirects >= properties.maxRedirects()) {
                    throw new SandboxViolationException("HTTP 重定向超过上限");
                }
                String location =
                        response.headers()
                                .firstValue("Location")
                                .orElseThrow(
                                        () -> new IllegalStateException("HTTP 重定向缺少 Location"));
                try {
                    uri = uri.resolve(location);
                } catch (RuntimeException e) {
                    throw new SandboxViolationException("无效重定向地址");
                }
                continue;
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("HTTP 状态码：" + response.statusCode());
            }
            return new String(response.body(), StandardCharsets.UTF_8);
        }
    }

    private static final class ResponseTooLargeException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    /** 在接收每批字节时检查上限；超限立即取消订阅，不先把整个正文读进内存。 */
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int cap;
        private final long deadlineNanos;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;

        BoundedBody(int cap, long deadlineNanos) {
            this.cap = cap;
            this.deadlineNanos = deadlineNanos;
        }

        public CompletionStage<byte[]> getBody() {
            return result;
        }

        public void onSubscribe(Flow.Subscription s) {
            subscription = s;
            s.request(1);
        }

        public void onNext(List<ByteBuffer> buffers) {
            if (System.nanoTime() >= deadlineNanos) {
                subscription.cancel();
                result.completeExceptionally(new TimeoutException());
                return;
            }
            for (var buffer : buffers) {
                if (buffer.remaining() > cap - bytes.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new ResponseTooLargeException());
                    return;
                }
                byte[] part = new byte[buffer.remaining()];
                buffer.get(part);
                bytes.writeBytes(part);
            }
            subscription.request(1);
        }

        public void onError(Throwable error) {
            result.completeExceptionally(new IllegalStateException("HTTP 正文读取失败"));
        }

        public void onComplete() {
            if (System.nanoTime() >= deadlineNanos) {
                result.completeExceptionally(new TimeoutException());
            } else {
                result.complete(bytes.toByteArray());
            }
        }
    }

    @Override
    public void close() {
        client.shutdownNow();
    }
}
