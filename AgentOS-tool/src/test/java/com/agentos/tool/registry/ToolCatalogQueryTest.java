package com.agentos.tool.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.core.agent.AgentLoader;
import com.agentos.core.tool.Redactor;
import com.agentos.core.tool.ToolCatalogView;
import com.agentos.core.tool.ToolDescriptor;
import com.agentos.core.tool.ToolPolicy;
import com.agentos.core.tool.ToolSourceDescriptor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

class ToolCatalogQueryTest {
    @TempDir Path root;

    @Test
    void globalAndAgentProjectionShareRegisteredStateAndNeverExecute() throws Exception {
        ToolCallback callback =
                new ToolCallback() {
                    public ToolDefinition getToolDefinition() {
                        return ToolDefinition.builder()
                                .name("web_search")
                                .description("搜索 secret-sentinel")
                                .inputSchema("{\"type\":\"object\"}")
                                .build();
                    }

                    public String call(String s) {
                        throw new AssertionError("不得执行");
                    }
                };
        var descriptor =
                new ToolDescriptor(
                        "web_search",
                        "搜索 secret-sentinel",
                        "{\"type\":\"object\"}",
                        "builtin",
                        null,
                        new ToolPolicy(true, Duration.ofSeconds(30), 10000),
                        callback.getToolMetadata(),
                        callback,
                        callback,
                        ToolDescriptor.Availability.UNCONFIGURED,
                        0);
        var registry = ToolRegistry.fromDescriptors(List.of(descriptor));
        registry.publish(
                List.of(descriptor),
                List.of(
                        new ToolSourceDescriptor(
                                "mcp:missing",
                                ToolSourceDescriptor.State.UNAVAILABLE,
                                ToolSourceDescriptor.Reason.CONNECT_FAILED)));
        var dir = Files.createDirectories(root.resolve("agents/demo"));
        Files.writeString(
                dir.resolve("AGENT.md"),
                "---\nname: demo\nprovider:\n  model: fixture\ntools: [web_search]\n---\n测试\n");
        var redactor = new Redactor(() -> List.of("secret-sentinel"));
        var query =
                new DefaultToolCatalogQuery(
                        registry, new AgentLoader(root.resolve("agents")), redactor);
        var global = query.snapshot(null);
        var agent = query.snapshot("demo");
        assertThat(global.version()).isEqualTo(agent.version());
        assertThat(global.tools()).hasSize(1);
        assertThat(global.tools().getFirst().authorized()).isNull();
        assertThat(global.tools().getFirst().visible()).isNull();
        assertThat(agent.tools().getFirst().authorized()).isTrue();
        assertThat(agent.tools().getFirst().visible()).isFalse();
        assertThat(agent.tools().getFirst().status()).isEqualTo("UNCONFIGURED");
        assertThat(global.sources())
                .extracting(ToolCatalogView.Source::source)
                .contains("mcp:missing");
        assertThat(global.toString()).doesNotContain("secret-sentinel", "inputSchema", "callback");
        assertThatThrownBy(() -> query.snapshot("../demo"))
                .isInstanceOf(com.agentos.core.error.AgentOSException.class);
    }
}
