package com.agentos.provider.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * US-1 离线测试使用的本地 HTTP 替身。
 *
 * <p>只在测试中使用，不访问外网，不读取 .env，也不打印请求中的敏感内容。
 *
 * <p>它模拟一个“会返回预先排队响应的模型服务”：测试先调用 {@link #enqueue(StubResponse)}
 * 安排响应，再把 {@link #baseUrl()} 交给 OpenAI 兼容客户端。客户端发请求后，
 * 这个类会记录请求并取出队首响应返回。
 *
 * <p>响应有三种：整包 JSON、逐段 flush 的 SSE 分片、永不响应（挂起到 {@link #close()}）。
 * 队列为空时收到的请求按“意外请求”处理：返回 500 并计数，不再静默返回 {@code {}}。
 */
public final class StubModelServer implements AutoCloseable {

    /** close 时等待处理线程退出的上限。 */
    private static final long TERMINATION_TIMEOUT_SECONDS = 5;

    /** JDK 自带的轻量 HTTP 服务，不需要启动外部进程或 Docker。 */
    private final HttpServer server;

    /** 处理请求的线程池；保存引用，close 时才能关闭它并确认线程全部退出。 */
    private final ExecutorService executor;

    /** 测试预先安排的响应，先进先出，便于模拟重试和多次调用。 */
    private final Deque<StubResponse> responses = new ArrayDeque<>();

    /** 已收到的请求，供测试断言请求次数、路径、请求头和请求体。 */
    private final List<StubRequest> requests = new ArrayList<>();

    /** close 时打开，解除“永不响应”的挂起。 */
    private final CountDownLatch closed = new CountDownLatch(1);

    /** 保证 server.stop 只执行一次，close 可重复调用。 */
    private final AtomicBoolean stopped = new AtomicBoolean();

    /** 队列为空时收到的请求数。 */
    private int unexpectedRequests;

    private StubModelServer(HttpServer server) {
        this.server = server;
        // 所有路径都交给同一个处理方法，测试不需要关心具体 SDK 请求路径。
        server.createContext("/", this::handle);
        // 使用虚拟线程处理并发请求，和项目的 JDK 21 运行基线保持一致。
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.start();
    }

    public static StubModelServer start() throws IOException {
        // 绑定 127.0.0.1，端口 0 让操作系统自动分配空闲端口，避免测试端口冲突。
        return new StubModelServer(HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0));
    }

    public synchronized StubModelServer enqueue(StubResponse response) {
        // synchronized 让测试线程和 HTTP 处理线程同时访问队列时不会产生竞态。
        responses.addLast(response);
        // 返回 this，允许 start().enqueue(...).enqueue(...) 链式配置。
        return this;
    }

    public synchronized int requestCount() {
        // 请求数是验证“是否重试”的关键证据。
        return requests.size();
    }

    public synchronized List<StubRequest> requests() {
        // 返回不可变列表快照，避免测试代码修改服务器内部记录。
        return List.copyOf(requests);
    }

    /** 队列耗尽后仍收到的请求数；大于 0 说明被测代码多发了请求。 */
    public synchronized int unexpectedRequestCount() {
        return unexpectedRequests;
    }

    /**
     * 有界等待，直到至少收到 {@code count} 个请求。
     *
     * <p>用于“请求已经到达替身”之后再做下一步（例如 close），避免靠 sleep 猜时间。
     * 等待被中断时恢复中断标志并返回 false。
     */
    public synchronized boolean awaitRequests(int count, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (requests.size() < count) {
                long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (remainingMillis <= 0) {
                    return false;
                }
                wait(remainingMillis);
            }
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public String baseUrl() {
        // OpenAI 客户端会在这个基础地址后拼接 /chat/completions 等路径。
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    /** 处理线程是否已全部退出；close 之后应为 true，用于证明没有泄漏线程。 */
    public boolean isTerminated() {
        return executor.isTerminated();
    }

    @Override
    public void close() {
        // try-with-resources 会自动调用 close，确保测试结束后释放端口和线程。
        // 先打开闸门，让“永不响应”的处理线程退出等待。
        closed.countDown();
        if (stopped.compareAndSet(false, true)) {
            // 停止监听并关闭所有连接；客户端看到连接被断开，而不是一直等待。
            server.stop(0);
        }
        // 中断仍停在延迟、SSE 闸门上的处理线程，再有界等待它们全部退出。
        executor.shutdownNow();
        try {
            executor.awaitTermination(TERMINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            // 调用方线程被中断：恢复标志交给上层处理，不吞掉。
            Thread.currentThread().interrupt();
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            // 先完整读取请求体，便于测试查看请求是否发到了预期路径。
            byte[] body;
            try (InputStream input = exchange.getRequestBody()) {
                body = input.readAllBytes();
            }
            StubRequest request =
                    new StubRequest(
                            exchange.getRequestMethod(),
                            exchange.getRequestURI().getPath(),
                            exchange.getRequestHeaders(),
                            body);
            StubResponse response;
            synchronized (this) {
                requests.add(request);
                // 没有排队响应时明确报错，避免多发的请求被一个假的成功响应掩盖。
                response = responses.pollFirst();
                if (response == null) {
                    unexpectedRequests++;
                    response = unexpected(request);
                }
                // 唤醒 awaitRequests 里等待的测试线程。
                notifyAll();
            }
            respond(exchange, response);
        } catch (InterruptedException interrupted) {
            // close 时 shutdownNow 会中断这里：恢复中断标记，连接已由 try-with-resources 关闭。
            Thread.currentThread().interrupt();
        }
    }

    private void respond(HttpExchange exchange, StubResponse response)
            throws IOException, InterruptedException {
        if (response.mode() == Mode.HANG) {
            // 永不响应：不写状态行，直到 close 打开闸门；之后连接被直接关闭。
            closed.await();
            return;
        }
        if (!response.delay().isZero()) {
            // 延迟用于模拟超时或慢 Provider。
            Thread.sleep(response.delay().toMillis());
        }
        // 先写状态码和响应头，再写响应体。
        exchange.getResponseHeaders().putAll(response.headers());
        OutputStream output = exchange.getResponseBody();
        if (response.mode() == Mode.FIXED) {
            byte[] payload = response.chunks().getFirst().getBytes(StandardCharsets.UTF_8);
            // 长度 -1 表示没有响应体；0 在 JDK HttpServer 里表示分块传输。
            exchange.sendResponseHeaders(
                    response.status(), payload.length == 0 ? -1 : payload.length);
            output.write(payload);
            return;
        }
        // SSE：长度 0 启用分块传输，每写一块就 flush，客户端可以逐段读到。
        exchange.sendResponseHeaders(response.status(), 0);
        List<String> chunks = response.chunks();
        for (int i = 0; i < chunks.size(); i++) {
            if (i > 0 && !response.chunkInterval().isZero()) {
                Thread.sleep(response.chunkInterval().toMillis());
            }
            output.write(chunks.get(i).getBytes(StandardCharsets.UTF_8));
            output.flush();
            if (i + 1 == response.pauseAfterChunks() && response.release() != null) {
                // 测试放行前停在这里，用来证明前面的分片已经单独送达。
                response.release().await();
            }
        }
    }

    private static StubResponse unexpected(StubRequest request) {
        String message = "unexpected request: " + request.method() + " " + request.path();
        return StubResponse.json(500, "{\"error\":{\"message\":\"" + message + "\"}}");
    }

    /** 记录到的一次请求；所有字段都是不可变快照。 */
    public record StubRequest(
            String method, String path, Map<String, List<String>> headers, byte[] body) {
        public StubRequest {
            // 请求头名按 HTTP 规则不区分大小写；值列表也复制成不可变的。
            Map<String, List<String>> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            headers.forEach((name, values) -> copy.put(name, List.copyOf(values)));
            headers = Collections.unmodifiableMap(copy);
            // byte[] 是可变对象；复制后，调用方修改原数组不会篡改测试记录。
            body = body.clone();
        }

        /** 返回副本：record 默认访问器会直接交出内部数组。 */
        @Override
        public byte[] body() {
            return body.clone();
        }

        /** 请求体按 UTF-8 解码。 */
        public String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }

        /** 第一个同名请求头的值，名称不区分大小写。 */
        public Optional<String> header(String name) {
            List<String> values = headers.get(name);
            return values == null || values.isEmpty()
                    ? Optional.empty()
                    : Optional.of(values.getFirst());
        }
    }

    /** 响应方式。 */
    public enum Mode {
        /** 一次写完、带 Content-Length 的响应。 */
        FIXED,
        /** 分块传输，每个分片单独 flush。 */
        SSE,
        /** 不写任何响应，挂起到 close。 */
        HANG
    }

    /**
     * 预先排队的一次响应，用工厂方法创建、用 {@code withXxx} 派生，不可变。
     *
     * @param chunks FIXED 时只有一个元素（整包响应体），SSE 时按顺序发送的 UTF-8 分片，HANG 时为空
     * @param pauseAfterChunks 发完第几个分片后等待 {@code release}；0 表示不暂停
     */
    public record StubResponse(
            Mode mode,
            int status,
            Map<String, List<String>> headers,
            List<String> chunks,
            Duration delay,
            Duration chunkInterval,
            int pauseAfterChunks,
            CountDownLatch release) {

        public StubResponse {
            // Map 和每个值列表都复制，防止外部修改排队脚本导致测试响应漂移。
            Map<String, List<String>> copy = new LinkedHashMap<>();
            headers.forEach((name, values) -> copy.put(name, List.copyOf(values)));
            headers = Collections.unmodifiableMap(copy);
            chunks = List.copyOf(chunks);
        }

        public static StubResponse json(int status, String body) {
            // 最常见的响应快捷方式：JSON、无延迟、明确 Content-Type。
            return new StubResponse(
                    Mode.FIXED,
                    status,
                    Map.of("Content-Type", List.of("application/json")),
                    List.of(body),
                    Duration.ZERO,
                    Duration.ZERO,
                    0,
                    null);
        }

        /** SSE 响应：每个元素原样写出并 flush，调用方自己写好 {@code data: ...\n\n}。 */
        public static StubResponse sse(int status, List<String> chunks) {
            return new StubResponse(
                    Mode.SSE,
                    status,
                    Map.of("Content-Type", List.of("text/event-stream")),
                    chunks,
                    Duration.ZERO,
                    Duration.ZERO,
                    0,
                    null);
        }

        /** 永不响应：收到请求后既不写状态行也不关连接，直到替身 close。 */
        public static StubResponse hang() {
            return new StubResponse(
                    Mode.HANG, 0, Map.of(), List.of(), Duration.ZERO, Duration.ZERO, 0, null);
        }

        /** 写状态行之前先等待一段时间。 */
        public StubResponse withDelay(Duration value) {
            return new StubResponse(
                    mode, status, headers, chunks, value, chunkInterval, pauseAfterChunks, release);
        }

        /** SSE 相邻分片之间的间隔。 */
        public StubResponse withChunkInterval(Duration value) {
            return new StubResponse(
                    mode, status, headers, chunks, delay, value, pauseAfterChunks, release);
        }

        /** 追加一个响应头。 */
        public StubResponse withHeader(String name, String value) {
            Map<String, List<String>> copy = new LinkedHashMap<>(headers);
            copy.put(name, List.of(value));
            return new StubResponse(
                    mode, status, copy, chunks, delay, chunkInterval, pauseAfterChunks, release);
        }

        /** SSE 发完前 {@code chunkCount} 个分片后停住，直到 {@code gate} 归零或替身 close。 */
        public StubResponse pauseAfter(int chunkCount, CountDownLatch gate) {
            return new StubResponse(
                    mode, status, headers, chunks, delay, chunkInterval, chunkCount, gate);
        }
    }
}
