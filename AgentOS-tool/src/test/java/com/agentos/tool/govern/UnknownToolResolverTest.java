package com.agentos.tool.govern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agentos.core.audit.ToolInvocationRecorder;
import com.agentos.core.tool.Redactor;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

class UnknownToolResolverTest {
    @Test
    void resolvesOnlyGovernedSafeDenialWithoutLookingUpAnotherBean() {
        var recorder = mock(ToolInvocationRecorder.class);
        when(recorder.begin(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var resolver = new UnknownToolResolver(recorder, new Redactor(List::of));

        ToolCallback denied = resolver.resolve("outside");

        assertThat(denied.getToolDefinition().name()).isEqualTo("outside");
        assertThat(denied.getToolDefinition().description()).contains("未授权");
        assertThat(denied.getToolDefinition().inputSchema()).isEqualTo("{\"type\":\"object\"}");
        assertThatThrownBy(() -> denied.call("{}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("显式 ToolContext");
    }
}
