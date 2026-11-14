package com.agentos.provider.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * US-1 离线测试使用的本地 HTTP 替身。
 *
 * <p>只在测试中使用，不访问外网，不读取 .env，也不打印请求中的敏感内容。
 */
public final class StubModelServer implements AutoCloseable {

    private final HttpServer server;
    private final Deque<StubResponse> responses = new ArrayDeque<>();
    private final List<StubRequest> requests = new ArrayList<>();

    private StubModelServer(HttpServer server) {
        this.server = server;
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    public static StubModelServer start() throws IOException {
        return new StubModelServer(HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0));
    }

    public synchronized StubModelServer enqueue(StubResponse response) {
        responses.addLast(response);
        return this;
    }

    public synchronized int requestCount() {
        return requests.size();
    }

    public synchronized List<StubRequest> requests() {
        return List.copyOf(requests);
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] body;
        try (InputStream input = exchange.getRequestBody()) {
            body = input.readAllBytes();
        }
        synchronized (this) {
            requests.add(
                    new StubRequest(
                            exchange.getRequestMethod(), exchange.getRequestURI().getPath(), body));
        }
        StubResponse response;
        synchronized (this) {
            response = responses.isEmpty() ? StubResponse.json(200, "{}") : responses.removeFirst();
        }
        if (response.delayMillis() > 0) {
            try {
                Thread.sleep(response.delayMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                exchange.close();
                return;
            }
        }
        exchange.getResponseHeaders().putAll(response.headers());
        byte[] payload = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(response.status(), payload.length);
        try {
            exchange.getResponseBody().write(payload);
        } finally {
            exchange.close();
        }
    }

    public record StubRequest(String method, String path, byte[] body) {
        public StubRequest {
            body = body.clone();
        }
    }

    public record StubResponse(
            int status,
            String body,
            long delayMillis,
            java.util.Map<String, List<String>> headers) {
        public static StubResponse json(int status, String body) {
            return new StubResponse(
                    status, body, 0, java.util.Map.of("Content-Type", List.of("application/json")));
        }
    }
}
