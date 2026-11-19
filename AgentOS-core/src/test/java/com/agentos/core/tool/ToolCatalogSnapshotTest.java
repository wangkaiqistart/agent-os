package com.agentos.core.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

class ToolCatalogSnapshotTest {
    @Test
    void retainsDeclarationOrderSeparateExecutionSetAndOriginalSource() {
        var a = callback("a");
        var b = callback("b");
        var binding = new LinkedHashMap<String, ToolDescriptor>();
        binding.put("a", descriptor(a, "java:plugin", ToolDescriptor.Availability.AVAILABLE));
        binding.put("b", descriptor(b, "builtin", ToolDescriptor.Availability.UNCONFIGURED));
        var declared = new ArrayList<>(List.of("b", "a"));
        var model = new ArrayList<>(List.of(a));
        var execution = new ArrayList<>(List.of(b, a));
        var snapshot =
                new ToolCatalogSnapshot(
                        "1",
                        Instant.now(),
                        new ArrayList<>(binding.values()),
                        new ArrayList<>(),
                        "agent",
                        declared,
                        new ArrayList<>(),
                        model,
                        execution,
                        binding);
        binding.clear();
        declared.clear();
        model.clear();
        execution.clear();
        assertThat(snapshot.declaredTools()).containsExactly("b", "a");
        assertThat(snapshot.modelCallbacks()).containsExactly(a);
        assertThat(snapshot.executionCallbacks()).containsExactly(b, a);
        assertThat(snapshot.sourceOf("a")).isEqualTo("java:plugin");
        assertThat(snapshot.sourceOf("absent")).isEqualTo("unknown");
        assertThatThrownBy(() -> snapshot.descriptors().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.sources().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.bindingByName().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.declaredServers().add("bad"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private ToolDescriptor descriptor(
            ToolCallback callback, String source, ToolDescriptor.Availability availability) {
        return new ToolDescriptor(
                callback.getToolDefinition().name(),
                "工具",
                "{\"type\":\"object\"}",
                source,
                null,
                new ToolPolicy(false, Duration.ofSeconds(30), 10000),
                callback.getToolMetadata(),
                callback,
                callback,
                availability,
                1);
    }

    private ToolCallback callback(String name) {
        return new ToolCallback() {
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name(name)
                        .description("工具")
                        .inputSchema("{\"type\":\"object\"}")
                        .build();
            }

            public String call(String input) {
                return "unused";
            }
        };
    }
}
