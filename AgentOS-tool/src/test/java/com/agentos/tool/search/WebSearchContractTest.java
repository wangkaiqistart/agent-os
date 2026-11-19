package com.agentos.tool.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.core.tool.ToolExecutionScope;
import com.agentos.core.turn.ToolContextKeys;
import com.agentos.core.turn.TurnExecutionBudget;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

class WebSearchContractTest {
    static SearchProviderConfig config(String url, String key) {
        return new SearchProviderConfig(
                "brave", url, key, 1000, 10, 1048576, Duration.ofSeconds(30));
    }

    static ToolExecutionScope scope() {
        return new ToolExecutionScope(
                null,
                "search",
                TurnExecutionBudget.start(Duration.ofSeconds(3), Duration.ofSeconds(1)),
                Duration.ofSeconds(2),
                () -> {});
    }

    @Test
    void inputBoundsAndConfigurationRejectBeforeProviderAndEmptyResultsRemainExplicit() {
        var calls = new AtomicInteger();
        SearchProvider provider =
                (q, n, s) -> {
                    calls.incrementAndGet();
                    return new SearchResult(List.of(), false, true);
                };
        try (var s = scope()) {
            var context = new ToolContext(Map.of(ToolContextKeys.TOOL_SCOPE, s));
            var tool = new WebSearchTools(config("https://example.org", "fixture"), provider);
            for (String q : List.of(" ", "q".repeat(1001))) {
                assertThatThrownBy(() -> tool.web_search(q, null, context))
                        .hasMessageContaining("INVALID_ARGUMENT");
            }
            for (int limit : List.of(0, 11)) {
                assertThatThrownBy(() -> tool.web_search("q", limit, context))
                        .hasMessageContaining("INVALID_ARGUMENT");
            }
            assertThatThrownBy(() ->
                            new WebSearchTools(config("https://example.org", ""), provider)
                                    .web_search("q", null, context))
                    .hasMessageContaining("UNCONFIGURED");
            assertThat(calls).hasValue(0);
            assertThat(tool.web_search("q", null, context))
                    .contains("\"items\":[]", "\"complete\":true");
            assertThat(calls).hasValue(1);
        }
    }

    @Test
    void resourceLimitsCanOnlyBeTightened() {
        assertThatThrownBy(() ->
                        new SearchProviderConfig(
                                "brave",
                                "https://example.org",
                                "fixture",
                                1001,
                                10,
                                1048576,
                                Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
