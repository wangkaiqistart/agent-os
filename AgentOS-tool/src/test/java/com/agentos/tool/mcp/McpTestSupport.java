package com.agentos.tool.mcp;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agentos.core.agent.AgentProfile;
import com.agentos.core.agent.LoadedAgent;
import com.agentos.core.audit.ToolInvocationRecorder;
import com.agentos.core.provider.Profile;
import com.agentos.core.tool.Redactor;
import com.agentos.core.turn.ToolCallLedger;
import com.agentos.core.turn.ToolContextKeys;
import com.agentos.core.turn.TurnContext;
import com.agentos.core.turn.TurnExecutionBudget;
import com.agentos.tool.govern.GovernedToolCallback;
import com.agentos.tool.process.ShellToolProperties;
import com.agentos.tool.registry.ToolRegistry;
import com.agentos.tool.sandbox.WhitelistSandbox;
import com.agentos.tool.support.LocalMcpServers;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ToolContext;

final class McpTestSupport {
    static AgentProfile profile(List<String> names, List<String> servers) {
        return new AgentProfile(
                "test", "", new Profile(null, "fixture", null, null, null), names, servers, null);
    }

    static McpClientService service(
            List<McpServerConfig> configs, java.nio.file.Path workspace, ToolRegistry registry)
            throws Exception {
        var recorder = mock(ToolInvocationRecorder.class);
        when(recorder.begin(any())).thenAnswer(i -> i.getArgument(0));
        return new McpClientService(
                configs,
                workspace,
                ShellToolProperties.defaults(
                        List.of(
                                new ShellToolProperties.Command(
                                        LocalMcpServers.python(),
                                        List.of(workspace.getParent()),
                                        Map.of()))),
                new WhitelistSandbox(List.of("127.0.0.1")),
                registry,
                descriptor ->
                        new GovernedToolCallback(
                                        descriptor, recorder, new Redactor(List::of), 10, 30)
                                .descriptor());
    }

    static ToolContext context(String name, Duration duration) {
        var profile = profile(List.of(name), List.of(name.substring(4, name.indexOf("__"))));
        var budget = TurnExecutionBudget.start(duration);
        var turn =
                new TurnContext(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        new LoadedAgent(profile, "", Map.of()),
                        "test",
                        1,
                        budget.deadline());
        return new ToolContext(
                Map.of(
                        ToolContextKeys.TURN_CONTEXT,
                        turn,
                        ToolContextKeys.TURN_BUDGET,
                        budget,
                        ToolContextKeys.TOOL_CALL_LEDGER,
                        new ToolCallLedger(
                                List.of(
                                        new AssistantMessage.ToolCall(
                                                "mcp-call", "function", name, "{}")),
                                () -> {}),
                        "UNKNOWN_INTERNAL_SENTINEL",
                        "private-context-sentinel"));
    }

    static void available(McpClientService service, String name) throws Exception {
        LocalMcpServers.waitFor(() -> service.generation(name).available(), 3500);
    }

    private McpTestSupport() {}
}
