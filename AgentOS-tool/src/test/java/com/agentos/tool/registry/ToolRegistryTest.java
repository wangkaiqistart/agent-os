package com.agentos.tool.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.core.agent.AgentProfile;
import com.agentos.core.provider.Profile;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

class ToolRegistryTest {
    static AgentProfile profile(List<String> tools) {
        return new AgentProfile("a", "", new Profile(null, "m", null, null, null), tools, null);
    }

    static ToolCallback callback(String name) {
        return new ToolCallback() {
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name(name)
                        .description("test")
                        .inputSchema("{}")
                        .build();
            }

            public String call(String input) {
                return "ok";
            }
        };
    }

    @Test
    void emptyAndSubsetListsOnlyExposeAuthorizedCallbacks() {
        var a = callback("a");
        var b = callback("b");
        var registry = new ToolRegistry(List.of(a, b));
        assertThat(registry.callbacks(profile(List.of()))).isEmpty();
        assertThat(registry.callbacks(profile(List.of("b")))).containsExactly(b);
    }

    @Test
    void rejectsDuplicatesAndUnknownNamesWithAgentFileAndField() {
        assertThatThrownBy(() -> new ToolRegistry(List.of(callback("a"), callback("a"))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ToolRegistry(List.of()).validate(profile(List.of("unknown"))))
                .hasMessageContaining("agents/a/AGENT.md")
                .hasMessageContaining("tools");
    }
}
