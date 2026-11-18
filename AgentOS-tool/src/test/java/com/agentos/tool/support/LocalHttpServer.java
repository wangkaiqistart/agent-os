package com.agentos.tool.support;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** 只绑定回环地址的 HTTP 替身，记录实际到达请求；不访问公网。 */
public final class LocalHttpServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    public final AtomicInteger calls = new AtomicInteger();

    public LocalHttpServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.start();
    }

    public String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    public void route(String path, HttpHandler handler) {
        server.createContext(
                path,
                e -> {
                    calls.incrementAndGet();
                    try {
                        handler.handle(e);
                    } finally {
                        e.close();
                    }
                });
    }

    public void text(String path, int status, String body) {
        route(
                path,
                e -> {
                    byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    e.sendResponseHeaders(status, bytes.length);
                    e.getResponseBody().write(bytes);
                });
    }

    public void redirect(String path, String target) {
        route(
                path,
                e -> {
                    if (target != null) {
                        e.getResponseHeaders().set("Location", target);
                    }
                    e.sendResponseHeaders(302, -1);
                });
    }

    /** 每个地址独立观测响应头、持续正文和客户端取消；自然完成不冒充取消成功。 */
    public SlowBodyProbe slowBody(String path) {
        var probe = new SlowBodyProbe();
        route(
                path,
                exchange -> {
                    probe.requests.incrementAndGet();
                    try (exchange) {
                        exchange.sendResponseHeaders(200, 0);
                        probe.headersSent.set(true);
                        byte[] chunk = new byte[1024];
                        // 最长持续约四秒，超过采样预算及其清理窗口，但总字节数不足 1 MiB。
                        for (int n = 0; n < 800; n++) {
                            exchange.getResponseBody().write(chunk);
                            exchange.getResponseBody().flush();
                            probe.chunksSent.incrementAndGet();
                            Thread.sleep(5);
                        }
                    } catch (IOException peerClosed) {
                        probe.cancelled.set(true);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } finally {
                        probe.closed.countDown();
                    }
                });
        return probe;
    }

    public static final class SlowBodyProbe {
        private final AtomicInteger requests = new AtomicInteger();
        private final AtomicBoolean headersSent = new AtomicBoolean();
        private final AtomicInteger chunksSent = new AtomicInteger();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final CountDownLatch closed = new CountDownLatch(1);

        public int requests() {
            return requests.get();
        }

        public boolean headersSent() {
            return headersSent.get();
        }

        public int chunksSent() {
            return chunksSent.get();
        }

        public boolean awaitCancelledAndClosed(Duration timeout) throws InterruptedException {
            return closed.await(timeout.toNanos(), TimeUnit.NANOSECONDS) && cancelled.get();
        }
    }

    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
