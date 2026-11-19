package com.agentos.tool.sandbox;

import com.agentos.core.interaction.ApprovalDecision;
import com.agentos.core.interaction.ApprovalRequest;
import com.agentos.core.interaction.TurnRuntimeCapabilities;
import com.agentos.core.tool.ApprovalRequiredException;
import com.agentos.core.tool.Sandbox;
import com.agentos.core.tool.SandboxAction;
import com.agentos.core.tool.SandboxViolationException;
import com.agentos.core.tool.ToolExecutionScope;
import com.agentos.core.turn.ToolContextKeys;
import com.agentos.core.turn.TurnContext;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;

/**
 * 有副作用的工具在动作前经过这里：先由沙箱判定，需要授权时向当前入口请求用户确认。
 *
 * <p>本会话已授权的动作直接放行；入口不支持交互（定时、REST 等无人值守）时立即拒绝。等待用户决定期间
 * 暂停工具自身与整轮计时，但取消和租约检查照常生效。拒绝仍以 {@link SandboxViolationException}
 * 抛出，走既有 sandbox_denied 审计并作为错误结果反馈给模型。
 */
public final class ToolApprovalGate {
    private static final Logger log = LoggerFactory.getLogger(ToolApprovalGate.class);

    private final Sandbox sandbox;
    private final SessionApprovalGrants grants;
    private final Duration timeout;

    public ToolApprovalGate(Sandbox sandbox, SessionApprovalGrants grants, Duration timeout) {
        this.sandbox = Objects.requireNonNull(sandbox);
        this.grants = Objects.requireNonNull(grants);
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("授权等待时间必须为正数");
        }
        this.timeout = timeout;
    }

    /** 默认网关：独立的会话授权记录、10 分钟等待上限；供未经 Spring 装配的工具构造及测试使用。 */
    public static ToolApprovalGate of(Sandbox sandbox) {
        return new ToolApprovalGate(sandbox, new SessionApprovalGrants(), Duration.ofMinutes(10));
    }

    public void enforce(SandboxAction action, ToolContext context) {
        try {
            sandbox.enforce(action);
            return;
        } catch (ApprovalRequiredException required) {
            approve(required, context);
        }
    }

    private void approve(ApprovalRequiredException required, ToolContext context) {
        var subject = required.subject();
        var values = context == null ? java.util.Map.<String, Object>of() : context.getContext();
        UUID sessionId =
                values.get(ToolContextKeys.TURN_CONTEXT) instanceof TurnContext turn
                        ? turn.sessionId()
                        : null;
        UUID turnId =
                values.get(ToolContextKeys.TURN_CONTEXT) instanceof TurnContext turn
                        ? turn.turnId()
                        : null;
        if (subject.sessionGrantAllowed() && grants.contains(sessionId, subject.grantKey())) {
            return;
        }
        if (!(values.get(ToolContextKeys.TOOL_SCOPE) instanceof ToolExecutionScope scope)) {
            throw required;
        }
        var capabilities =
                values.get(ToolContextKeys.RUNTIME_CAPABILITIES)
                                instanceof TurnRuntimeCapabilities supplied
                        ? supplied
                        : TurnRuntimeCapabilities.NONE;
        scope.checkpoint();
        ApprovalDecision decision;
        try (var paused = scope.pauseClock()) {
            decision =
                    capabilities
                            .interaction()
                            .approve(
                                    new ApprovalRequest(
                                            UUID.randomUUID(),
                                            sessionId,
                                            turnId,
                                            scope.callId(),
                                            subject,
                                            System.nanoTime() + timeout.toNanos()),
                                    scope);
        }
        // 只记录类别和决定，不记录路径、参数或 URL。
        log.info("工具授权 type={} outcome={}", subject.type(), decision.outcome());
        switch (decision.outcome()) {
            case ALLOW_ONCE -> {
                return;
            }
            case ALLOW_SESSION -> {
                if (subject.sessionGrantAllowed()) {
                    grants.grant(sessionId, subject.grantKey());
                }
                return;
            }
            case DENY ->
                    throw new SandboxViolationException(
                            decision.feedback() == null
                                    ? "DENIED：用户拒绝了该操作；不要重复同样的请求，可换一种做法或询问用户"
                                    : "DENIED：用户拒绝了该操作；用户说明：" + decision.feedback());
            case UNSUPPORTED ->
                    throw new SandboxViolationException(
                            "DENIED：该操作需要用户授权，但当前入口无人值守、无法询问；可改用其他方式完成任务，或请部署者在配置中预先授权");
            case TIMED_OUT -> throw new SandboxViolationException("DENIED：等待用户授权超时，操作未执行");
            case CANCELLED, EOF -> throw new SandboxViolationException("DENIED：授权请求已取消，操作未执行");
            default -> throw new SandboxViolationException("DENIED：未获得用户授权");
        }
    }
}
