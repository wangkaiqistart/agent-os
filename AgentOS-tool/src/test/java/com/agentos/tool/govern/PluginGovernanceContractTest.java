package com.agentos.tool.govern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agentos.core.audit.ToolInvocationRecorder;
import com.agentos.core.tool.TransientToolException;
import com.agentos.tool.registry.JavaToolDiscovery;
import com.agentos.tool.registry.ToolPolicyResolver;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.execution.ToolExecutionException;

class PluginGovernanceContractTest {
    static class StartedPlugin {
        final AtomicInteger actions = new AtomicInteger();

        @Tool(name = "http_get", description = "已开始失败")
        @com.agentos.tool.annotation.ToolPolicy(idempotent = true)
        public String execute() {
            actions.incrementAndGet();
            throw new TransientToolException(true);
        }
    }

    static class BoundedPlugin {
        @Tool(name = "http_get", description = "有界结果")
        @com.agentos.tool.annotation.ToolPolicy(maxResultChars = 90)
        public String execute() {
            return "x".repeat(1000);
        }
    }

    static class SlowPlugin {
        @Tool(name = "http_get", description = "有界等待")
        @com.agentos.tool.annotation.ToolPolicy(timeout = "40ms")
        public String execute() throws InterruptedException {
            Thread.sleep(10000);
            return "late";
        }
    }

    private GovernedToolCallback governed(Object bean) {
        var descriptor =
                new JavaToolDiscovery(new ToolPolicyResolver(), GovernedToolCallbackTest.REDACTOR)
                        .discover(Map.of("plugin", bean), List.of(), List.of())
                        .getFirst();
        var recorder = mock(ToolInvocationRecorder.class);
        when(recorder.begin(any())).thenAnswer(i -> i.getArgument(0));
        return new GovernedToolCallback(
                descriptor, recorder, GovernedToolCallbackTest.REDACTOR, 10, 30);
    }

    @Test
    void annotationPolicyActuallyControlsRetryAndResultSize() {
        var plugin = new StartedPlugin();
        var started = governed(plugin);
        assertThatThrownBy(() ->
                        started.call(
                                "{}",
                                GovernedToolCallbackTest.context(Duration.ofSeconds(3), () -> {})))
                .isInstanceOf(ToolExecutionException.class);
        assertThat(plugin.actions).hasValue(1);
        var result =
                governed(new BoundedPlugin())
                        .call(
                                "{}",
                                GovernedToolCallbackTest.context(Duration.ofSeconds(3), () -> {}));
        assertThat(result.length()).isLessThanOrEqualTo(90);
        assertThat(result).contains("截断");
    }

    @Test
    void annotationTimeoutInterruptsCooperatingPlugin() {
        var tool = governed(new SlowPlugin());
        long started = System.nanoTime();
        assertThatThrownBy(() ->
                        tool.call(
                                "{}",
                                GovernedToolCallbackTest.context(Duration.ofSeconds(3), () -> {})))
                .isInstanceOf(ToolExecutionException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started))
                .isLessThan(Duration.ofMillis(1300));
    }
}
