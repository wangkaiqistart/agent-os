package com.agentos.tool.support;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
