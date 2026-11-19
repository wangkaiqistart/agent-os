package com.agentos.tool.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.core.tool.Redactor;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;

class ToolPluginPolicyTest {
    static class Policies {
        @Tool(description = "默认")
        public String plain(String input, ToolContext context) {
            return input;
        }

        @Tool(description = "指定", returnDirect = true)
        @com.agentos.tool.annotation.ToolPolicy(
                idempotent = true,
                timeout = "120ms",
                maxResultChars = 120)
        public String configured(String input, ToolContext context) {
            return input;
        }

        @Tool(description = "无效")
        @com.agentos.tool.annotation.ToolPolicy(timeout = "0s")
        public String invalid() {
            return "";
        }

        @Tool(description = "不支持")
        public Optional<String> optional() {
            return Optional.empty();
        }

        @Tool(description = "不支持")
        public CompletableFuture<String> asynchronous() {
            return CompletableFuture.completedFuture("");
        }
    }

    @Test
    void annotationDefaultsAndExplicitPolicyRemainDistinctAndValid() throws Exception {
        var resolver = new ToolPolicyResolver();
        var plain =
                resolver.resolve(
                        Policies.class.getMethod("plain", String.class, ToolContext.class));
        assertThat(plain.idempotent()).isFalse();
        assertThat(plain.timeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(plain.maxResultChars()).isEqualTo(10000);
        var configured =
                resolver.resolve(
                        Policies.class.getMethod("configured", String.class, ToolContext.class));
        assertThat(configured.idempotent()).isTrue();
        assertThat(configured.timeout()).isEqualTo(Duration.ofMillis(120));
        assertThat(configured.maxResultChars()).isEqualTo(120);
        assertThat(resolver.resolve(configured)).isSameAs(configured);
        for (String name : List.of("invalid", "optional", "asynchronous")) {
            assertThatThrownBy(() -> resolver.resolve(Policies.class.getMethod(name)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    static class ContextPlugin {
        @Tool(name = "contextual", description = "隐参", returnDirect = true)
        public String contextual(String input, ToolContext context) {
            return input;
        }
    }

    @Test
    void officialMetadataAndHiddenContextAreRetained() {
        var definitions =
                new JavaToolDiscovery(new ToolPolicyResolver(), new Redactor(List::of))
                        .discover(Map.of("contextual", new ContextPlugin()), List.of(), List.of());
        assertThat(definitions).hasSize(1);
        assertThat(definitions.getFirst().metadata().returnDirect()).isTrue();
        assertThat(definitions.getFirst().inputSchema())
                .contains("input")
                .doesNotContain("context", "ToolContext");
    }
}
