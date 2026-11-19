package com.agentos.tool.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.core.agent.AgentProfile;
import com.agentos.core.provider.Profile;
import com.agentos.core.tool.UnknownAgentToolException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

class ToolRegistryTest {
    @Test
    void failedPublicationPreservesOldVersionAndSnapshotDoesNotRebind() {
        var a = callback("a");
        var registry = new ToolRegistry(List.of(a));
        var snapshot = registry.resolve(profile(List.of("a")));
        assertThatThrownBy(() ->
                        registry.publish(
                                List.of(
                                        snapshot.descriptors().getFirst(),
                                        snapshot.descriptors().getFirst()),
                                List.of()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(registry.resolve(profile(List.of("a"))).version()).isEqualTo(snapshot.version());
        registry.publish(List.of(), List.of());
        assertThat(snapshot.modelCallbacks()).containsExactly(a);
        assertThat(snapshot.bindingByName().get("a").callback()).isSameAs(a);
        assertThatThrownBy(() -> registry.resolve(profile(List.of("a"))))
                .isInstanceOf(UnknownAgentToolException.class);
    }

    static AgentProfile profile(List<String> tools) {
        return new AgentProfile("a", "", new Profile(null, "m", null, null, null), tools, null);
    }

    static ToolCallback callback(String name) {
        return new ToolCallback() {
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name(name)
                        .description("test")
                        .inputSchema("{\"type\":\"object\"}")
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
                .isInstanceOf(UnknownAgentToolException.class)
                .hasMessageContaining("agents/a/AGENT.md")
                .hasMessageContaining("tools")
                .hasMessageContaining("unknown");
    }

    @Test
    void rejectsIncompleteDefinitionsBeforeRegistration() {
        ToolCallback missingDescription =
                new ToolCallback() {
                    public ToolDefinition getToolDefinition() {
                        return new ToolDefinition() {
                            public String name() {
                                return "missing_description";
                            }

                            public String description() {
                                return " ";
                            }

                            public String inputSchema() {
                                return "{}";
                            }
                        };
                    }

                    public String call(String input) {
                        return "unused";
                    }
                };
        ToolCallback invalidSchema =
                new ToolCallback() {
                    public ToolDefinition getToolDefinition() {
                        return ToolDefinition.builder()
                                .name("invalid_schema")
                                .description("test")
                                .inputSchema("not-json")
                                .build();
                    }

                    public String call(String input) {
                        return "unused";
                    }
                };

        assertThatThrownBy(() -> new ToolRegistry(List.of(missingDescription)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ToolRegistry(List.of(invalidSchema)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void agentToolNamesKeepDeclarationOrderAndRejectDuplicates() {
        assertThatThrownBy(() -> profile(List.of("b", "b")))
                .isInstanceOf(com.agentos.core.tool.DuplicateAgentToolException.class)
                .hasMessageContaining("agents/a/AGENT.md")
                .hasMessageContaining("tools")
                .hasMessageContaining("重复")
                .hasMessageContaining("b");
    }
}
