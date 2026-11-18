package com.agentos.tool.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.core.turn.ToolContextKeys;
import com.agentos.core.turn.TurnDeadlineExceededException;
import com.agentos.core.turn.TurnExecutionBudget;
import com.agentos.tool.sandbox.WhitelistSandbox;
import com.agentos.tool.support.LocalHttpServer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

/** 验证 HTTP 响应头之后的 body 消费也共享同一个轮次预算。 */
class HttpToolsDeadlineTest {
    @Test
    void slowContinuousBodyIsCancelledAtSharedDeadline() throws Exception {
        try (var server = new LocalHttpServer();
                var tools =
                        new HttpTools(
                                new HttpToolProperties(
                                        List.of("127.0.0.1"),
                                        "AgentOS-test",
                                        3,
                                        1024 * 1024,
                                        Duration.ofSeconds(2),
                                        10_000),
                                new WhitelistSandbox(List.of("127.0.0.1")))) {
            server.route(
                    "/slow",
                    exchange -> {
                        exchange.sendResponseHeaders(200, 0);
                        exchange.getResponseBody().write('a');
                        exchange.getResponseBody().flush();
                        try {
                            Thread.sleep(800);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                        exchange.getResponseBody().write('b');
                    });
            var budget = TurnExecutionBudget.start(Duration.ofMillis(120));
            assertThatThrownBy(() ->
                            tools.http_get(
                                    server.url("/slow"),
                                    new ToolContext(Map.of(ToolContextKeys.TURN_BUDGET, budget))))
                    .isInstanceOf(TurnDeadlineExceededException.class);
        }
    }

    @Test
    void expiredBudgetDoesNotSendHttpRequest() throws Exception {
        try (var server = new LocalHttpServer();
                var tools =
                        new HttpTools(
                                new HttpToolProperties(
                                        List.of("127.0.0.1"),
                                        "AgentOS-test",
                                        3,
                                        1024,
                                        Duration.ofSeconds(2),
                                        10_000),
                                new WhitelistSandbox(List.of("127.0.0.1")))) {
            server.text("/never", 200, "unexpected");
            var budget = TurnExecutionBudget.start(Duration.ofNanos(1));
            while (!budget.expired()) {
                Thread.onSpinWait();
            }
            assertThatThrownBy(() ->
                            tools.http_get(
                                    server.url("/never"),
                                    new ToolContext(Map.of(ToolContextKeys.TURN_BUDGET, budget))))
                    .isInstanceOf(TurnDeadlineExceededException.class);
            org.assertj.core.api.Assertions.assertThat(server.calls).hasValue(0);
        }
    }

    @Test
    void sustainedBodyP95RemainsBoundedAcrossTwentyRequestsWithoutRetry() throws Exception {
        var cold = new java.util.concurrent.atomic.AtomicBoolean(true);
        var sandbox = new WhitelistSandbox(List.of("127.0.0.1"));
        try (var server = new LocalHttpServer();
                var tools =
                        new HttpTools(
                                new HttpToolProperties(
                                        List.of("127.0.0.1"),
                                        "test",
                                        3,
                                        1048576,
                                        Duration.ofSeconds(5),
                                        10000),
                                action -> {
                                    // 确定性模拟首次初始化开销，不能把未进入正文的请求计入慢正文样本。
                                    if (cold.compareAndSet(true, false)) {
                                        try {
                                            Thread.sleep(750);
                                        } catch (InterruptedException interrupted) {
                                            Thread.currentThread().interrupt();
                                            throw new IllegalStateException("测试准备被中断");
                                        }
                                    }
                                    sandbox.enforce(action);
                                })) {
            // 同一个工具实例独立预热；准备时间不混入样本，也不重置任何采样中的预算。
            server.text("/warmup", 200, "ready");
            assertThat(
                            tools.http_get(
                                    server.url("/warmup"),
                                    new ToolContext(
                                            Map.of(
                                                    ToolContextKeys.TURN_BUDGET,
                                                    TurnExecutionBudget.start(
                                                            Duration.ofSeconds(5))))))
                    .isEqualTo("ready");
            assertThat(cold).isFalse();
            assertThat(server.calls).hasValue(1);
            int beforeSamples = server.calls.get();
            Duration sampleBudget = Duration.ofMillis(500);
            var samples = new java.util.ArrayList<Long>();
            for (int n = 0; n < 20; n++) {
                String path = "/chunks/" + n;
                var probe = server.slowBody(path);
                String url = server.url(path);
                var budget = TurnExecutionBudget.start(sampleBudget);
                long start = System.nanoTime();
                assertThatThrownBy(() ->
                                tools.http_get(
                                        url,
                                        new ToolContext(
                                                Map.of(ToolContextKeys.TURN_BUDGET, budget))))
                        .isInstanceOf(TurnDeadlineExceededException.class);
                // 不用外层 server.close 中断处理器来假装客户端已取消；计时包含连接取消收尾。
                assertThat(probe.awaitCancelledAndClosed(Duration.ofSeconds(1)))
                        .as("第 %s 个样本必须由客户端取消并关闭响应", n + 1)
                        .isTrue();
                assertThat(probe.headersSent()).as("样本 %s 的响应头", n + 1).isTrue();
                assertThat(probe.chunksSent()).as("样本 %s 的持续正文", n + 1).isGreaterThan(1);
                assertThat(probe.requests()).as("样本 %s 不得重试", n + 1).isOne();
                assertThat(server.calls).hasValue(beforeSamples + n + 1);
                samples.add(
                        java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                                System.nanoTime() - start));
            }
            samples.sort(Long::compareTo);
            assertThat(samples).hasSize(20);
            assertThat(samples.get(18)).isLessThanOrEqualTo(sampleBudget.toMillis() + 1000);
            assertThat(server.calls).hasValue(beforeSamples + 20);
            org.slf4j.LoggerFactory.getLogger(getClass())
                    .info(
                            "deadline_acceptance scenario=slow_body samples={} budget_ms={} p95_ms={}"
                                    + " requests={} bodies_started={} cancelled_and_closed={} warmup_requests={}",
                            samples.size(),
                            sampleBudget.toMillis(),
                            samples.get(18),
                            server.calls.get() - beforeSamples,
                            samples.size(),
                            samples.size(),
                            beforeSamples);
        }
    }
}
