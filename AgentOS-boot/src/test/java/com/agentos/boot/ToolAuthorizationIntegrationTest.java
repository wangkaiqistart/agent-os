package com.agentos.boot;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentos.core.agent.AgentLoader;
import com.agentos.core.agent.ContextLoader;
import com.agentos.core.agent.LoadedAgent;
import com.agentos.core.provider.ProviderCatalog;
import com.agentos.core.session.SessionManager;
import com.agentos.core.session.TurnStore;
import com.agentos.core.turn.DefaultAgentService;
import com.agentos.core.turn.ProcessRequest;
import com.agentos.core.turn.ReActLoop;
import com.agentos.core.turn.TurnBudgetRegistry;
import org.junit.jupiter.api.Test;

/** 授权名单在申请轮次前失败，避免模型、消息和工具审计产生副作用。 */
class ToolAuthorizationIntegrationTest {
    @Test
    void unknownConfiguredToolFailsBeforeTurnCreation() {
        var loader = mock(AgentLoader.class);
        var contextLoader = mock(ContextLoader.class);
        var providers = mock(ProviderCatalog.class);
        var sessions = mock(SessionManager.class);
        var turns = mock(TurnStore.class);
        var loop = mock(ReActLoop.class);
        var loaded = mock(LoadedAgent.class);
        var profile = mock(com.agentos.core.agent.AgentProfile.class);
        when(loader.load("restricted")).thenReturn(loaded);
        when(contextLoader.load(loaded)).thenReturn(loaded);
        when(loaded.profile()).thenReturn(profile);
        doThrow(
                        new IllegalArgumentException(
                                "Agent tools 未注册：agents/restricted/AGENT.md，tools=outside"))
                .when(loop)
                .validate(profile);

        var service =
                new DefaultAgentService(
                        loader,
                        contextLoader,
                        providers,
                        sessions,
                        turns,
                        loop,
                        new TurnBudgetRegistry());

        assertThatThrownBy(() ->
                        service.process(
                                new ProcessRequest("test", "user", "restricted", "hello", "test")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside")
                .hasMessageContaining("agents/restricted/AGENT.md");
        verify(turns, never())
                .startTurn(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
        verify(loop, never())
                .run(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
    }
}
