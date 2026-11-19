package com.agentos.tool.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.core.tool.ToolSourceDescriptor;
import com.agentos.tool.registry.ToolRegistry;
import com.agentos.tool.support.LocalMcpServers;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpLifecycleContractTest {
    @Test
    void completePaginationHasOneBudgetAndAThousandToolCeiling() throws Exception {
        for (boolean overflow : List.of(true, false)) {
            try (var fixture = new LocalMcpServers("streamable-http")) {
                fixture.control(
                        overflow
                                ? Map.of("toolCount", 1001, "pageSize", 500)
                                : Map.of(
                                        "toolCount",
                                        3,
                                        "pageSize",
                                        1,
                                        "initDelay",
                                        2,
                                        "pageDelay",
                                        3));
                var registry = new ToolRegistry(List.of());
                try (var service =
                        McpTestSupport.service(
                                List.of(fixture.configuration("pages")),
                                fixture.directory(),
                                registry)) {
                    long begin = System.nanoTime();
                    service.start();
                    LocalMcpServers.waitFor(
                            () -> {
                                try {
                                    return fixture.stats().path("list").asInt() > 0;
                                } catch (java.io.IOException e) {
                                    return false;
                                }
                            },
                            4000);
                    LocalMcpServers.waitFor(
                            () ->
                                    service.sources().getFirst().state()
                                            == ToolSourceDescriptor.State.UNAVAILABLE,
                            11000);
                    assertThat(service.generation("pages").available()).isFalse();
                    assertThat(registry.descriptors()).isEmpty();
                    assertThat(Duration.ofNanos(System.nanoTime() - begin))
                            .isLessThan(Duration.ofMillis(11100));
                    assertThat(fixture.stats().path("list").asInt()).isBetween(1, 3);
                }
            }
        }
    }

    @Test
    void oldGenerationNeverRevivesAndRecoveryCannotExpandAuthorization() throws Exception {
        try (var fixture = new LocalMcpServers("streamable-http")) {
            var registry = new ToolRegistry(List.of());
            try (var service =
                    McpTestSupport.service(
                            List.of(fixture.configuration("news")),
                            fixture.directory(),
                            registry)) {
                service.start();
                McpTestSupport.available(service, "news");
                var profile = McpTestSupport.profile(List.of("mcp_news__echo"), List.of("news"));
                var old = registry.resolve(profile);
                var generation = service.generation("news");
                assertThat(old.modelCallbacks()).hasSize(1);
                service.invalidate("news", ToolSourceDescriptor.Reason.CONNECT_FAILED);
                assertThat(registry.resolve(profile).modelCallbacks()).isEmpty();
                int before = fixture.stats().path("call").asInt();
                assertThatThrownBy(() ->
                                old.executionCallbacks()
                                        .getFirst()
                                        .call(
                                                "{}",
                                                McpTestSupport.context(
                                                        "mcp_news__echo", Duration.ofSeconds(2))))
                        .isInstanceOf(RuntimeException.class);
                assertThat(fixture.stats().path("call").asInt()).isEqualTo(before);
                fixture.control(Map.of("names", List.of("echo", "new_tool")));
                McpTestSupport.available(service, "news");
                assertThat(service.generation("news").id()).isGreaterThan(generation.id());
                assertThat(generation.available()).isFalse();
                assertThat(registry.resolve(profile).modelCallbacks())
                        .extracting(c -> c.getToolDefinition().name())
                        .containsExactly("mcp_news__echo");
                service.invalidate("news", ToolSourceDescriptor.Reason.CONNECT_FAILED);
                fixture.control(Map.of("names", List.of("new_tool")));
                McpTestSupport.available(service, "news");
                assertThatThrownBy(() -> registry.resolve(profile))
                        .isInstanceOf(RuntimeException.class);
            }
        }
    }

    @Test
    void doubleDeclarationAndDistinctSourcesAreRequiredAndBadSourceDoesNotDelayStart()
            throws Exception {
        try (var good = new LocalMcpServers("streamable-http");
                var bad = new LocalMcpServers("streamable-http")) {
            bad.control(Map.of("initDelay", 15));
            var registry = new ToolRegistry(List.of());
            try (var service =
                    McpTestSupport.service(
                            List.of(good.configuration("good"), bad.configuration("bad")),
                            good.directory(),
                            registry)) {
                long started = System.nanoTime();
                service.start();
                assertThat(Duration.ofNanos(System.nanoTime() - started))
                        .isLessThan(Duration.ofSeconds(1));
                McpTestSupport.available(service, "good");
                assertThat(
                                registry.resolve(
                                                McpTestSupport.profile(
                                                        List.of("mcp_good__echo"), List.of("good")))
                                        .modelCallbacks())
                        .hasSize(1);
                assertThatThrownBy(() ->
                                registry.resolve(
                                        McpTestSupport.profile(
                                                List.of("mcp_good__echo"), List.of())))
                        .isInstanceOf(RuntimeException.class);
                assertThatThrownBy(() ->
                                registry.resolve(
                                        McpTestSupport.profile(List.of(), List.of("unknown"))))
                        .isInstanceOf(RuntimeException.class);
            }
        }
        assertThat(McpReconnectScheduler.delay(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(McpReconnectScheduler.delay(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(McpReconnectScheduler.delay(20)).isEqualTo(Duration.ofSeconds(300));
    }
}
