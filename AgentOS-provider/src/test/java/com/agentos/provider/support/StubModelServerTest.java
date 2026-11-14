package com.agentos.provider.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.provider.support.StubModelServer.StubRequest;
import com.agentos.provider.support.StubModelServer.StubResponse;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * StubModelServer 自身的行为测试：用 JDK HttpClient 真实访问 127.0.0.1。
 *
 * <p>等待一律用 latch / Future 的有界超时，不靠长 sleep，降低偶发失败。
 */
class StubModelServerTest {

    /** 所有有界等待的上限；正常情况下远小于这个值就会返回。 */
    private static final long WAIT_SECONDS = 5;

    private StubModelServer server;

    private HttpClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = StubModelServer.start();
        // 替身只支持 HTTP/1.1，固定版本避免客户端发起 h2c 升级。
        client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    }

    @AfterEach
    void tearDown() {
        client.close();
        server.close();
    }

    @Test
    @DisplayName("按脚本顺序返回 JSON，并记录方法、路径、请求头、请求体和次数")
    void recordsRequestsInOrderAndReturnsScriptedJson() throws Exception {
        server.enqueue(StubResponse.json(201, "{\"n\":1}").withHeader("X-Stub", "first"))
                .enqueue(StubResponse.json(202, "{\"n\":2}"));

        HttpResponse<String> first =
                client.send(
                        request("/chat/completions")
                                .header("Authorization", "Bearer test-key")
                                .header("X-Trace", "a")
                                .header("X-Trace", "b")
                                .POST(HttpRequest.BodyPublishers.ofString("你好"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> second =
                client.send(request("/models").GET().build(), HttpResponse.BodyHandlers.ofString());

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(first.body()).isEqualTo("{\"n\":1}");
        assertThat(first.headers().firstValue("Content-Type")).hasValue("application/json");
        assertThat(first.headers().firstValue("X-Stub")).hasValue("first");
        assertThat(second.statusCode()).isEqualTo(202);
        assertThat(second.body()).isEqualTo("{\"n\":2}");

        assertThat(server.requestCount()).isEqualTo(2);
        List<StubRequest> requests = server.requests();
        StubRequest sent = requests.get(0);
        assertThat(sent.method()).isEqualTo("POST");
        assertThat(sent.path()).isEqualTo("/v1/chat/completions");
        // 请求头名不区分大小写读取；同名多值按发送顺序保留。
        assertThat(sent.header("authorization")).hasValue("Bearer test-key");
        assertThat(sent.headers().get("x-trace")).containsExactly("a", "b");
        assertThat(sent.bodyText()).isEqualTo("你好");
        assertThat(requests.get(1).method()).isEqualTo("GET");
        assertThat(requests.get(1).path()).isEqualTo("/v1/models");
        assertThat(requests.get(1).body()).isEmpty();
        assertThat(server.unexpectedRequestCount()).isZero();
    }

    @Test
    @DisplayName("请求快照不可变：body 访问器防御复制，headers 与列表不可修改")
    void requestSnapshotsAreImmutable() throws Exception {
        server.enqueue(StubResponse.json(200, "{}"));
        client.send(
                request("/chat/completions")
                        .header("X-Trace", "a")
                        .POST(HttpRequest.BodyPublishers.ofString("abc"))
                        .build(),
                HttpResponse.BodyHandlers.discarding());

        List<StubRequest> snapshot = server.requests();
        StubRequest recorded = snapshot.getFirst();
        byte[] leaked = recorded.body();
        leaked[0] = 'X';

        assertThat(server.requests().getFirst().bodyText()).isEqualTo("abc");
        assertThat(recorded.bodyText()).isEqualTo("abc");
        assertThatThrownBy(() -> recorded.headers().put("x-new", List.of("v")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> recorded.headers().get("x-trace").add("b"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.add(recorded))
                .isInstanceOf(UnsupportedOperationException.class);

        // 构造时传入的可变数组和 Map 之后再改，也不影响记录。
        byte[] body = "abc".getBytes(StandardCharsets.UTF_8);
        List<String> values = new ArrayList<>(List.of("v1"));
        Map<String, List<String>> headers = new HashMap<>(Map.of("X-A", values));
        StubRequest direct = new StubRequest("POST", "/p", headers, body);
        body[0] = 'X';
        values.add("v2");
        headers.put("X-B", List.of("v"));
        assertThat(direct.bodyText()).isEqualTo("abc");
        assertThat(direct.headers()).containsOnlyKeys("X-A");
        assertThat(direct.headers().get("x-a")).containsExactly("v1");

        // 已取出的快照不会随后续请求增长。
        server.enqueue(StubResponse.json(200, "{}"));
        client.send(request("/models").build(), HttpResponse.BodyHandlers.discarding());
        assertThat(snapshot).hasSize(1);
        assertThat(server.requests()).hasSize(2);
    }

    @Test
    @DisplayName("响应脚本在入队前后都不受外部请求头列表修改影响")
    void responseHeadersAreImmutableSnapshots() throws Exception {
        List<String> values = new ArrayList<>(List.of("original"));
        Map<String, List<String>> headers = new HashMap<>(Map.of("X-Script", values));
        StubResponse response =
                new StubResponse(
                        StubModelServer.Mode.FIXED,
                        200,
                        headers,
                        List.of("{}"),
                        Duration.ZERO,
                        Duration.ZERO,
                        0,
                        null);
        server.enqueue(response);
        values.add("changed");
        headers.put("X-Later", List.of("added"));

        HttpResponse<String> received =
                client.send(request("/models").build(), HttpResponse.BodyHandlers.ofString());
        assertThat(received.headers().allValues("X-Script")).containsExactly("original");
        assertThat(received.headers().firstValue("X-Later")).isEmpty();
        assertThatThrownBy(() -> response.headers().get("X-Script").add("changed"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("没有排队响应时明确返回 unexpected request 错误并计数")
    void unscriptedRequestFailsExplicitly() throws Exception {
        HttpResponse<String> response =
                client.send(request("/models").build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).contains("unexpected request").contains("GET /v1/models");
        assertThat(server.unexpectedRequestCount()).isEqualTo(1);
        // 意外请求也算收到的请求，便于断言“不该有的调用发生了”。
        assertThat(server.requestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("SSE 分片逐段 flush：放行前只能读到第 1 块，放行后读到其余分片")
    void sseChunksAreFlushedOneByOne() throws Exception {
        String c1 = "data: {\"delta\":\"你\"}\n\n";
        String c2 = "data: {\"delta\":\"好\"}\n\n";
        String c3 = "data: [DONE]\n\n";
        CountDownLatch release = new CountDownLatch(1);
        server.enqueue(StubResponse.sse(200, List.of(c1, c2, c3)).pauseAfter(1, release));

        HttpResponse<InputStream> response =
                client.sendAsync(
                                request("/chat/completions")
                                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                                        .build(),
                                HttpResponse.BodyHandlers.ofInputStream())
                        .get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("text/event-stream");
        try (InputStream in = response.body()) {
            // 闸门还关着时就能读到完整的第 1 块，证明它没有等整包写完才发出。
            assertThat(text(readWithin(in, utf8(c1).length))).isEqualTo(c1);
            assertThat(in.available()).isZero();

            release.countDown();
            assertThat(text(readWithin(in, utf8(c2 + c3).length))).isEqualTo(c2 + c3);
            assertThat(readWithin(in, 1)).isEmpty();
        }
    }

    @Test
    @DisplayName("SSE 分片间隔生效，响应体按 UTF-8 原样拼接")
    void sseChunkIntervalSpacesChunks() throws Exception {
        List<String> chunks = List.of("data: 一\n\n", "data: 二\n\n", "data: [DONE]\n\n");
        server.enqueue(StubResponse.sse(200, chunks).withChunkInterval(Duration.ofMillis(100)));

        long start = System.nanoTime();
        HttpResponse<String> response =
                client.send(
                        request("/chat/completions").build(), HttpResponse.BodyHandlers.ofString());
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(response.body()).isEqualTo(String.join("", chunks));
        // 3 个分片之间有 2 个间隔。
        assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(200));
    }

    @Test
    @DisplayName("延迟响应：短延迟正常返回，长延迟让客户端超时")
    void delayedResponse() throws Exception {
        server.enqueue(StubResponse.json(200, "{}").withDelay(Duration.ofMillis(100)))
                .enqueue(StubResponse.json(200, "{}").withDelay(Duration.ofSeconds(WAIT_SECONDS)));

        long start = System.nanoTime();
        HttpResponse<String> ok =
                client.send(request("/a").build(), HttpResponse.BodyHandlers.ofString());
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(Duration.ofNanos(System.nanoTime() - start))
                .isGreaterThanOrEqualTo(Duration.ofMillis(100));

        HttpRequest slow = request("/b").timeout(Duration.ofMillis(200)).build();
        assertThatThrownBy(() -> client.send(slow, HttpResponse.BodyHandlers.discarding()))
                .isInstanceOf(HttpTimeoutException.class);
        assertThat(server.requestCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("永不响应：客户端超时；close 解除挂起，未超时的请求以 IOException 结束")
    void hangUntilClose() throws Exception {
        server.enqueue(StubResponse.hang()).enqueue(StubResponse.hang());

        HttpRequest timed = request("/a").timeout(Duration.ofMillis(200)).build();
        assertThatThrownBy(() -> client.send(timed, HttpResponse.BodyHandlers.discarding()))
                .isInstanceOf(HttpTimeoutException.class);

        CompletableFuture<HttpResponse<Void>> pending =
                client.sendAsync(request("/b").build(), HttpResponse.BodyHandlers.discarding());
        assertThat(server.awaitRequests(2, Duration.ofSeconds(WAIT_SECONDS))).isTrue();
        assertThat(pending).isNotDone();

        server.close();

        assertThatThrownBy(() -> pending.get(WAIT_SECONDS, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(IOException.class);
        assertThat(server.isTerminated()).isTrue();
    }

    @Test
    @DisplayName("SSE 停在闸门时 close：客户端读取以错误结束，处理线程全部退出")
    void closeReleasesPausedStream() throws Exception {
        String c1 = "data: 1\n\n";
        server.enqueue(
                StubResponse.sse(200, List.of(c1, "data: 2\n\n"))
                        .pauseAfter(1, new CountDownLatch(1)));
        HttpResponse<InputStream> response =
                client.sendAsync(request("/s").build(), HttpResponse.BodyHandlers.ofInputStream())
                        .get(WAIT_SECONDS, TimeUnit.SECONDS);

        try (InputStream in = response.body()) {
            assertThat(text(readWithin(in, utf8(c1).length))).isEqualTo(c1);
            CompletableFuture<byte[]> rest = CompletableFuture.supplyAsync(() -> readAll(in));

            server.close();

            assertThat(server.isTerminated()).isTrue();
            // 截断的分块响应必须报错，而不是一直挂着（TimeoutException）或假装读完。
            assertThatThrownBy(() -> rest.get(WAIT_SECONDS, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class);
        }
    }

    @Test
    @DisplayName("close 释放端口与线程，可重复调用")
    void closeReleasesResourcesAndIsIdempotent() throws Exception {
        String baseUrl = server.baseUrl();
        server.close();
        server.close();

        assertThat(server.isTerminated()).isTrue();
        HttpRequest late = HttpRequest.newBuilder(URI.create(baseUrl + "/models")).build();
        assertThatThrownBy(() -> client.send(late, HttpResponse.BodyHandlers.discarding()))
                .isInstanceOf(ConnectException.class);
    }

    @Test
    @DisplayName("等待被中断时恢复调用线程的中断标志")
    void interruptedWaitRestoresInterruptFlag() {
        Thread.currentThread().interrupt();
        boolean arrived = server.awaitRequests(1, Duration.ofSeconds(WAIT_SECONDS));
        // Thread.interrupted() 读取并清除标志，避免影响后续测试。
        boolean restored = Thread.interrupted();
        assertThat(arrived).isFalse();
        assertThat(restored).isTrue();

        Thread.currentThread().interrupt();
        server.close();
        assertThat(Thread.interrupted()).isTrue();
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(server.baseUrl() + path));
    }

    /** 在独立线程读取 n 个字节，有界等待；替身没有 flush 时这里会超时失败而不是卡死。 */
    private static byte[] readWithin(InputStream in, int n) throws Exception {
        return CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                return in.readNBytes(n);
                            } catch (IOException e) {
                                throw new java.io.UncheckedIOException(e);
                            }
                        })
                .get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    private static byte[] readAll(InputStream in) {
        try {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }
}
