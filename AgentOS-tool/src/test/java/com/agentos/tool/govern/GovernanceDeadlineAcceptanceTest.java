package com.agentos.tool.govern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agentos.core.audit.ToolInvocationRecorder;
import com.agentos.core.tool.ToolPolicy;
import com.agentos.core.turn.TurnDeadlineExceededException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** 两类等待各运行 20 次；断言实际执行次数和 p95，而非只测计时辅助方法。 */
class GovernanceDeadlineAcceptanceTest {
    @Test
    void slowToolAndAuditWaitEachStayWithinBudgetForTwentyRuns() {
        for (String scenario : java.util.List.of("slow_tool", "audit_wait")) {
            var elapsed = new ArrayList<Long>();
            for (int n = 0; n < 20; n++) {
                var release = new CountDownLatch(1);
                var calls = new AtomicInteger();
                var recorder = mock(ToolInvocationRecorder.class);
                when(recorder.begin(any()))
                        .thenAnswer(i -> {
                            if (scenario.equals("audit_wait")) {
                                release.await();
                            }
                            return i.getArgument(0);
                        });
                doAnswer(i -> null).when(recorder).complete(any());
                var tool =
                        new GovernedToolCallback(
                                GovernedToolCallbackTest.callback(c -> {
                                    calls.incrementAndGet();
                                    try {
                                        release.await();
                                    } catch (InterruptedException interrupted) {
                                        Thread.currentThread().interrupt();
                                    }
                                    return "late";
                                }),
                                new ToolPolicy(true, Duration.ofSeconds(2), 10000),
                                recorder,
                                GovernedToolCallbackTest.REDACTOR);
                var context = GovernedToolCallbackTest.context(Duration.ofMillis(80), () -> {});
                long start = System.nanoTime();
                try {
                    assertThatThrownBy(() -> tool.call("{}", context))
                            .isInstanceOf(TurnDeadlineExceededException.class);
                } finally {
                    release.countDown();
                }
                elapsed.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
                assertThat(calls).hasValue(scenario.equals("slow_tool") ? 1 : 0);
            }
            elapsed.sort(Long::compareTo);
            long p95 = elapsed.get(18);
            assertThat(p95).isLessThanOrEqualTo(1080);
            LoggerFactory.getLogger(getClass())
                    .info(
                            "deadline_acceptance scenario={} samples=20 budget_ms=80 p95_ms={}",
                            scenario,
                            p95);
        }
    }
}
