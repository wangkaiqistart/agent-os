package com.agentos.tool.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.core.interaction.ApprovalDecision;
import com.agentos.core.interaction.ApprovalRequest;
import com.agentos.core.interaction.InteractionPort;
import com.agentos.core.interaction.InteractionResult;
import com.agentos.core.interaction.TurnRuntimeCapabilities;
import com.agentos.core.tool.ActionType;
import com.agentos.core.tool.ApprovalRequiredException;
import com.agentos.core.tool.SandboxAction;
import com.agentos.core.tool.SandboxViolationException;
import com.agentos.core.tool.ToolExecutionScope;
import com.agentos.core.turn.ToolContextKeys;
import com.agentos.core.turn.TurnContext;
import com.agentos.core.turn.TurnEventPayload;
import com.agentos.core.turn.TurnEventPublisher;
import com.agentos.core.turn.TurnExecutionBudget;
import com.agentos.tool.file.FileAccessPolicy;
import com.agentos.tool.file.FileToolProperties;
import com.agentos.tool.process.ShellToolProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;

class ToolApprovalGateTest {
    @TempDir Path workspace;
    @TempDir Path external;

    /** 记录收到的授权请求，并按给定规则作答。 */
    private static final class ScriptedPort implements InteractionPort {
        final List<ApprovalRequest> requests = new ArrayList<>();
        final Function<ApprovalRequest, ApprovalDecision> answer;

        ScriptedPort(Function<ApprovalRequest, ApprovalDecision> answer) {
            this.answer = answer;
        }

        @Override
        public InteractionResult ask(
                com.agentos.core.interaction.InteractionRequest request, ToolExecutionScope scope) {
            return InteractionResult.of(InteractionResult.Status.UNSUPPORTED);
        }

        @Override
        public ApprovalDecision approve(ApprovalRequest request, ToolExecutionScope scope) {
            requests.add(request);
            return answer.apply(request);
        }
    }

    private WhitelistSandbox sandbox(
            List<String> domains, List<ShellToolProperties.Command> commands) {
        var files =
                new FileAccessPolicy(
                        workspace, FileToolProperties.defaults(List.of(external), List.of()));
        return new WhitelistSandbox(domains, files, ShellToolProperties.defaults(commands));
    }

    private static ToolContext context(
            UUID sessionId, InteractionPort port, ToolExecutionScope scope) {
        var profile =
                new com.agentos.core.agent.AgentProfile(
                        "test",
                        "",
                        new com.agentos.core.provider.Profile(null, "fixture", null, null, null),
                        List.of(),
                        List.of(),
                        null);
        var turn =
                new TurnContext(
                        sessionId,
                        UUID.randomUUID(),
                        new com.agentos.core.agent.LoadedAgent(profile, "", Map.of()),
                        "cli",
                        1,
                        java.time.Instant.now().plusSeconds(30));
        return new ToolContext(
                Map.of(
                        ToolContextKeys.TURN_CONTEXT,
                        turn,
                        ToolContextKeys.TOOL_SCOPE,
                        scope,
                        ToolContextKeys.RUNTIME_CAPABILITIES,
                        new TurnRuntimeCapabilities(port)));
    }

    private static ToolExecutionScope scope() {
        return new ToolExecutionScope(
                null,
                "call-1",
                TurnExecutionBudget.start(Duration.ofSeconds(5)),
                Duration.ofSeconds(5),
                () -> {});
    }

    @Test
    void sandboxSeparatesAllowedApprovalRequiredAndDenied() throws Exception {
        Files.writeString(workspace.resolve("inside.txt"), "x");
        Files.writeString(external.resolve("ref.txt"), "x");
        var sandbox = sandbox(List.of("api.example.com"), List.of());
        // 工作区内与配置读根内读取直接放行。
        sandbox.enforce(new SandboxAction(ActionType.FILE_READ, "inside.txt"));
        sandbox.enforce(
                new SandboxAction(ActionType.FILE_READ, external.resolve("ref.txt").toString()));
        // 工作区内写入需要授权；Agent 定义只允许逐次确认。
        assertThatThrownBy(
                        () -> sandbox.enforce(new SandboxAction(ActionType.FILE_WRITE, "new.txt")))
                .isInstanceOfSatisfying(
                        ApprovalRequiredException.class,
                        e -> assertThat(e.subject().sessionGrantAllowed()).isTrue());
        Files.createDirectories(workspace.resolve("agents/main"));
        assertThatThrownBy(() ->
                        sandbox.enforce(
                                new SandboxAction(ActionType.FILE_WRITE, "agents/main/AGENT.md")))
                .isInstanceOfSatisfying(
                        ApprovalRequiredException.class,
                        e -> assertThat(e.subject().sessionGrantAllowed()).isFalse());
        // 工作区外未配置写根：硬拒绝，不进入询问。
        assertThatThrownBy(() ->
                        sandbox.enforce(
                                new SandboxAction(
                                        ActionType.FILE_WRITE, external.resolve("x").toString())))
                .isInstanceOf(SandboxViolationException.class)
                .isNotInstanceOf(ApprovalRequiredException.class);
        // HTTP：白名单内放行，其他域名需要授权，非 http/https 硬拒绝。
        sandbox.enforce(new SandboxAction(ActionType.HTTP, "https://api.example.com/v1"));
        assertThatThrownBy(() ->
                        sandbox.enforce(
                                new SandboxAction(
                                        ActionType.HTTP,
                                        "https://other.example.org/a?token=secret-value",
                                        List.of("POST"))))
                .isInstanceOfSatisfying(
                        ApprovalRequiredException.class,
                        e -> {
                            assertThat(e.subject().grantKey())
                                    .isEqualTo("http:POST:other.example.org");
                            assertThat(String.join("\n", e.subject().lines()))
                                    .doesNotContain("secret-value");
                        });
        assertThatThrownBy(() ->
                        sandbox.enforce(new SandboxAction(ActionType.HTTP, "file:///etc/passwd")))
                .isNotInstanceOf(ApprovalRequiredException.class)
                .isInstanceOf(SandboxViolationException.class);
    }

    @Test
    void shellNeedsApprovalUnlessPreApprovedAndCwdMustStayInWorkspace() throws Exception {
        Path echo = Path.of("/bin/echo").toRealPath();
        Path cwd = workspace.toRealPath();
        var open = sandbox(List.of(), List.of());
        assertThatThrownBy(() ->
                        open.enforce(
                                new SandboxAction(
                                        ActionType.SHELL,
                                        echo.toString(),
                                        List.of(cwd.toString(), "hi"))))
                .isInstanceOfSatisfying(
                        ApprovalRequiredException.class,
                        e -> assertThat(e.subject().grantKey()).isEqualTo("shell:" + echo + " hi"));
        assertThatThrownBy(() ->
                        open.enforce(
                                new SandboxAction(
                                        ActionType.SHELL,
                                        echo.toString(),
                                        List.of(external.toRealPath().toString()))))
                .isNotInstanceOf(ApprovalRequiredException.class)
                .hasMessageContaining("DENIED");
        var preApproved =
                sandbox(
                        List.of(),
                        List.of(new ShellToolProperties.Command(echo, List.of(cwd), Map.of())));
        preApproved.enforce(
                new SandboxAction(ActionType.SHELL, echo.toString(), List.of(cwd.toString())));
    }

    @Test
    void gateAsksOnceRemembersSessionGrantsAndReturnsFeedback() {
        var gate =
                new ToolApprovalGate(
                        sandbox(List.of(), List.of()),
                        new SessionApprovalGrants(),
                        Duration.ofSeconds(5));
        var action = new SandboxAction(ActionType.HTTP, "https://news.example.com/");
        UUID session = UUID.randomUUID();
        try (var scope = scope()) {
            var once =
                    new ScriptedPort(r -> ApprovalDecision.of(ApprovalDecision.Outcome.ALLOW_ONCE));
            gate.enforce(action, context(session, once, scope));
            gate.enforce(action, context(session, once, scope));
            assertThat(once.requests).hasSize(2);

            var always =
                    new ScriptedPort(
                            r -> ApprovalDecision.of(ApprovalDecision.Outcome.ALLOW_SESSION));
            gate.enforce(action, context(session, always, scope));
            gate.enforce(action, context(session, always, scope));
            assertThat(always.requests).hasSize(1);
            assertThat(always.requests.getFirst().sessionId()).isEqualTo(session);
            assertThat(always.requests.getFirst().toolCallId()).isEqualTo("call-1");

            // 会话授权不跨会话生效。
            var other = new ScriptedPort(r -> ApprovalDecision.deny("请改用 http_get 读取官方 RSS"));
            assertThatThrownBy(() -> gate.enforce(action, context(UUID.randomUUID(), other, scope)))
                    .isInstanceOf(SandboxViolationException.class)
                    .hasMessageContaining("用户说明：请改用 http_get 读取官方 RSS");
            assertThat(other.requests).hasSize(1);
        }
    }

    @Test
    void unattendedAndTimedOutApprovalsDenyWithoutAction() {
        var gate =
                new ToolApprovalGate(
                        sandbox(List.of(), List.of()),
                        new SessionApprovalGrants(),
                        Duration.ofSeconds(5));
        var action = new SandboxAction(ActionType.FILE_WRITE, "out.txt");
        try (var scope = scope()) {
            assertThatThrownBy(() ->
                            gate.enforce(
                                    action,
                                    context(UUID.randomUUID(), InteractionPort.UNSUPPORTED, scope)))
                    .hasMessageContaining("无人值守");
            var late =
                    new ScriptedPort(r -> ApprovalDecision.of(ApprovalDecision.Outcome.TIMED_OUT));
            assertThatThrownBy(() -> gate.enforce(action, context(UUID.randomUUID(), late, scope)))
                    .hasMessageContaining("超时");
        }
        assertThat(workspace.resolve("out.txt")).doesNotExist();
        // 已在白名单内的动作不询问。
        var silent =
                new ScriptedPort(r -> {
                    throw new AssertionError("不应询问");
                });
        try (var scope = scope()) {
            new ToolApprovalGate(
                            sandbox(List.of("ok.example.com"), List.of()),
                            new SessionApprovalGrants(),
                            Duration.ofSeconds(5))
                    .enforce(
                            new SandboxAction(ActionType.HTTP, "https://ok.example.com/"),
                            context(UUID.randomUUID(), silent, scope));
        }
    }

    @Test
    void approvalWaitPausesTheTurnBudget() {
        var budget = TurnExecutionBudget.start(Duration.ofMillis(200));
        var gate =
                new ToolApprovalGate(
                        sandbox(List.of(), List.of()),
                        new SessionApprovalGrants(),
                        Duration.ofSeconds(5));
        try (var scope =
                new ToolExecutionScope(null, "slow", budget, Duration.ofMillis(200), () -> {})) {
            var slow =
                    new ScriptedPort(r -> {
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return ApprovalDecision.of(ApprovalDecision.Outcome.ALLOW_ONCE);
                    });
            gate.enforce(
                    new SandboxAction(ActionType.HTTP, "https://slow.example.com/"),
                    context(UUID.randomUUID(), slow, scope));
            assertThat(budget.expired()).isFalse();
            scope.checkpoint();
        }
    }

    /** 记录发布的事件及发布时刻。 */
    private record Published(TurnEventPayload payload, java.time.Instant at) {}

    private static ToolContext contextWithEvents(
            UUID sessionId, InteractionPort port, ToolExecutionScope scope, List<Published> out) {
        var values = new java.util.HashMap<>(context(sessionId, port, scope).getContext());
        TurnEventPublisher publisher =
                payload -> out.add(new Published(payload, java.time.Instant.now()));
        values.put(ToolContextKeys.TURN_EVENTS, publisher);
        return new ToolContext(values);
    }

    @Test
    void approvalEventsCarryRequestAndDecisionButNotDenialReason() {
        var outcomes =
                List.of(
                        ApprovalDecision.of(ApprovalDecision.Outcome.ALLOW_ONCE),
                        ApprovalDecision.of(ApprovalDecision.Outcome.ALLOW_SESSION),
                        ApprovalDecision.deny("请改用 DENIAL_REASON_SENTINEL"),
                        ApprovalDecision.of(ApprovalDecision.Outcome.TIMED_OUT),
                        ApprovalDecision.of(ApprovalDecision.Outcome.UNSUPPORTED),
                        ApprovalDecision.of(ApprovalDecision.Outcome.CANCELLED));
        var expected =
                List.of(
                        TurnEventPayload.ApprovalDecisionKind.ALLOW_ONCE,
                        TurnEventPayload.ApprovalDecisionKind.ALLOW_SESSION,
                        TurnEventPayload.ApprovalDecisionKind.DENY,
                        TurnEventPayload.ApprovalDecisionKind.TIMED_OUT,
                        TurnEventPayload.ApprovalDecisionKind.UNATTENDED,
                        TurnEventPayload.ApprovalDecisionKind.CANCELLED);
        for (int i = 0; i < outcomes.size(); i++) {
            var decision = outcomes.get(i);
            var port = new ScriptedPort(request -> decision);
            var events = new ArrayList<Published>();
            var gate = ToolApprovalGate.of(sandbox(List.of(), List.of()));
            try {
                gate.enforce(
                        new SandboxAction(ActionType.FILE_WRITE, "new.txt"),
                        contextWithEvents(UUID.randomUUID(), port, scope(), events));
            } catch (SandboxViolationException denied) {
                assertThat(decision.allowed()).isFalse();
            }
            assertThat(events).hasSize(2);
            var requested = (TurnEventPayload.ApprovalRequested) events.get(0).payload();
            var resolved = (TurnEventPayload.ApprovalResolved) events.get(1).payload();
            assertThat(requested.requestId()).isEqualTo(port.requests.getFirst().requestId());
            assertThat(requested.toolCallId()).isEqualTo("call-1");
            assertThat(requested.actionType()).isEqualTo("FILE_WRITE");
            assertThat(requested.title()).isNotBlank();
            assertThat(requested.lines()).isEqualTo(port.requests.getFirst().subject().lines());
            assertThat(requested.sessionGrantAllowed()).isTrue();
            assertThat(resolved.requestId()).isEqualTo(requested.requestId());
            assertThat(resolved.decision()).isEqualTo(expected.get(i));
            assertThat(events.toString()).doesNotContain("DENIAL_REASON_SENTINEL");
        }
    }

    @Test
    void sessionGrantSkipsApprovalEvents() {
        var sessionId = UUID.randomUUID();
        var gate = ToolApprovalGate.of(sandbox(List.of(), List.of()));
        var port =
                new ScriptedPort(
                        request -> ApprovalDecision.of(ApprovalDecision.Outcome.ALLOW_SESSION));
        var first = new ArrayList<Published>();
        gate.enforce(
                new SandboxAction(ActionType.FILE_WRITE, "a.txt"),
                contextWithEvents(sessionId, port, scope(), first));
        var second = new ArrayList<Published>();
        gate.enforce(
                new SandboxAction(ActionType.FILE_WRITE, "b.txt"),
                contextWithEvents(sessionId, port, scope(), second));
        assertThat(first).hasSize(2);
        assertThat(second).isEmpty();
    }

    @Test
    void approvalEventTimestampsReflectWaitWhileClockPaused() {
        var port =
                new ScriptedPort(request -> {
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return ApprovalDecision.of(ApprovalDecision.Outcome.ALLOW_ONCE);
                });
        var events = new ArrayList<Published>();
        ToolApprovalGate.of(sandbox(List.of(), List.of()))
                .enforce(
                        new SandboxAction(ActionType.FILE_WRITE, "slow.txt"),
                        contextWithEvents(UUID.randomUUID(), port, scope(), events));
        assertThat(Duration.between(events.get(0).at(), events.get(1).at()))
                .isGreaterThanOrEqualTo(Duration.ofMillis(300));
    }

    @Test
    void interactionFailureResolvesAsCancelled() {
        var port =
                new ScriptedPort(request -> {
                    throw new com.agentos.core.turn.LeaseLostException();
                });
        var events = new ArrayList<Published>();
        assertThatThrownBy(() ->
                        ToolApprovalGate.of(sandbox(List.of(), List.of()))
                                .enforce(
                                        new SandboxAction(ActionType.FILE_WRITE, "x.txt"),
                                        contextWithEvents(
                                                UUID.randomUUID(), port, scope(), events)))
                .isInstanceOf(com.agentos.core.turn.LeaseLostException.class);
        assertThat(((TurnEventPayload.ApprovalResolved) events.getLast().payload()).decision())
                .isEqualTo(TurnEventPayload.ApprovalDecisionKind.CANCELLED);
    }
}
