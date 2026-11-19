package com.agentos.tool.http;

import com.agentos.core.tool.ToolExecutionScope;
import com.agentos.core.turn.TurnDeadlineExceededException;
import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 单个scope约束连接、响应头、正文与取消，不附带远端原文到错误。 */
public final class BoundedHttpIo {
    private BoundedHttpIo() {}

    public static HttpResponse<byte[]> exchange(
            HttpClient client, HttpRequest request, ToolExecutionScope scope, int maxBytes) {
        if (maxBytes <= 0 || maxBytes > 1048576) {
            throw new IllegalArgumentException("HTTP正文上限无效");
        }
        scope.checkpoint();
        var bounded =
                HttpRequest.newBuilder(request, (name, value) -> true)
                        .timeout(Duration.ofNanos(Math.max(1, scope.remainingNanos())))
                        .build();
        scope.possiblyStarted();
        if (request.method().equals("POST")) {
            scope.submissionPossiblySent();
        }
        var future = client.sendAsync(bounded, info -> new BoundedBody(maxBytes, scope));
        var registration =
                scope.register(deadline -> {
                    future.cancel(true);
                    return future.isDone();
                });
        try {
            for (; ; ) {
                try {
                    var response =
                            future.get(
                                    Math.max(
                                            1,
                                            Math.min(
                                                    scope.remainingNanos(),
                                                    TimeUnit.MILLISECONDS.toNanos(50))),
                                    TimeUnit.NANOSECONDS);
                    scope.checkpoint();
                    return response;
                } catch (TimeoutException wait) {
                    scope.checkpoint();
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            scope.checkpoint();
            throw new IllegalStateException("HTTP 请求中断");
        } catch (ExecutionException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof ResponseTooLargeException) {
                    throw new IllegalStateException("HTTP 响应正文超过大小上限");
                }
                if (cause instanceof ToolExecutionScope.ToolTimeoutException timeout) {
                    throw timeout;
                }
                if (cause instanceof TurnDeadlineExceededException deadline) {
                    throw deadline;
                }
                if (cause instanceof com.agentos.core.turn.LeaseLostException lease) {
                    throw lease;
                }
                if (cause instanceof java.net.http.HttpTimeoutException) {
                    throw new ToolExecutionScope.ToolTimeoutException();
                }
            }
            throw new IllegalStateException("HTTP 请求失败");
        } finally {
            if (!future.isDone()) {
                future.cancel(true);
            }
            registration.close();
        }
    }

    private static final class ResponseTooLargeException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    /** 按接收块检查，超过上限立即取消订阅，不能先聚合全部正文。 */
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int cap;
        private final ToolExecutionScope scope;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;

        BoundedBody(int cap, ToolExecutionScope scope) {
            this.cap = cap;
            this.scope = scope;
        }

        public CompletionStage<byte[]> getBody() {
            return result;
        }

        public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            value.request(1);
        }

        public void onNext(List<ByteBuffer> buffers) {
            try {
                scope.checkpoint();
                for (var buffer : buffers) {
                    if (buffer.remaining() > cap - bytes.size()) {
                        throw new ResponseTooLargeException();
                    }
                    byte[] part = new byte[buffer.remaining()];
                    buffer.get(part);
                    bytes.writeBytes(part);
                }
                subscription.request(1);
            } catch (RuntimeException failure) {
                subscription.cancel();
                result.completeExceptionally(failure);
            }
        }

        public void onError(Throwable error) {
            result.completeExceptionally(new IllegalStateException("HTTP 正文读取失败"));
        }

        public void onComplete() {
            try {
                scope.checkpoint();
                result.complete(bytes.toByteArray());
            } catch (RuntimeException failure) {
                result.completeExceptionally(failure);
            }
        }
    }
}
