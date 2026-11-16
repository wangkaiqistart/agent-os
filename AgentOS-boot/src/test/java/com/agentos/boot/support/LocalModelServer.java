package com.agentos.boot.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Boot 纵向测试使用的本地 OpenAI 兼容模型替身。 */
public final class LocalModelServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicInteger calls = new AtomicInteger();
    private final List<String> requests = new CopyOnWriteArrayList<>();

    /** 仅捕获测试中的合成请求，生产代码不增加 prompt 日志。 */
    public List<String> requests() {
        return List.copyOf(requests);
    }

    private LocalModelServer(HttpServer server) {
        this.server = server;
        server.createContext("/v1/chat/completions", this::complete);
        server.setExecutor(executor);
        server.start();
    }

    public static LocalModelServer start() throws IOException {
        return new LocalModelServer(HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0));
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public int calls() {
        return calls.get();
    }

    private void complete(HttpExchange exchange) throws IOException {
        try (exchange) {
            calls.incrementAndGet();
            requests.add(
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body =
                    "{\"id\":\"local-1\",\"object\":\"chat.completion\",\"model\":\"test-model\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"local answer\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":2,\"total_tokens\":3}}"
                            .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
