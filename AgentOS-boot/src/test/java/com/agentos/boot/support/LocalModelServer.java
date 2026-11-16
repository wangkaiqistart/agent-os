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
    private final java.util.Queue<String> responses =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** 只供本地集成测试安排模型动作，不访问真实模型。 */
    public void reset(String... scriptedResponses) {
        calls.set(0);
        requests.clear();
        responses.clear();
        responses.addAll(java.util.List.of(scriptedResponses));
    }

    /** 本地脚本的最终建议；和原默认纯文本替身共用同一兼容协议。 */
    public static String textResponse(String answer) {
        return tools.jackson.databind.json.JsonMapper.builder()
                .build()
                .writeValueAsString(
                        java.util.Map.of(
                                "id",
                                "local-answer",
                                "object",
                                "chat.completion",
                                "model",
                                "test-model",
                                "choices",
                                java.util.List.of(
                                        java.util.Map.of(
                                                "index",
                                                0,
                                                "message",
                                                java.util.Map.of(
                                                        "role", "assistant", "content", answer),
                                                "finish_reason",
                                                "stop")),
                                "usage",
                                java.util.Map.of(
                                        "prompt_tokens",
                                        1,
                                        "completion_tokens",
                                        2,
                                        "total_tokens",
                                        3)));
    }

    public static String toolResponse(String id, String url) {
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        String arguments = json.writeValueAsString(java.util.Map.of("url", url));
        return json.writeValueAsString(
                java.util.Map.of(
                        "id",
                        "local-tool",
                        "object",
                        "chat.completion",
                        "model",
                        "test-model",
                        "choices",
                        java.util.List.of(
                                java.util.Map.of(
                                        "index",
                                        0,
                                        "message",
                                        java.util.Map.of(
                                                "role",
                                                "assistant",
                                                "content",
                                                "查天气",
                                                "tool_calls",
                                                java.util.List.of(
                                                        java.util.Map.of(
                                                                "id",
                                                                id,
                                                                "type",
                                                                "function",
                                                                "function",
                                                                java.util.Map.of(
                                                                        "name",
                                                                        "http_get",
                                                                        "arguments",
                                                                        arguments)))),
                                        "finish_reason",
                                        "tool_calls")),
                        "usage",
                        java.util.Map.of(
                                "prompt_tokens", 1, "completion_tokens", 2, "total_tokens", 3)));
    }

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
            String scripted = responses.poll();
            byte[] body =
                    (scripted == null
                                    ? "{\"id\":\"local-1\",\"object\":\"chat.completion\",\"model\":\"test-model\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"local answer\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":2,\"total_tokens\":3}}"
                                    : scripted)
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
