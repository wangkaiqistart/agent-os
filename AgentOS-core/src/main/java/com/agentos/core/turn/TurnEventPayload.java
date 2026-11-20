package com.agentos.core.turn;

import com.agentos.core.interaction.ApprovalDecision;
import com.agentos.core.interaction.InteractionResult;
import com.agentos.core.provider.FailureKind;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 运行事件的类型相关负载，契约见 specs/007-run-event-stream/contracts/run-events.md。
 *
 * <p>每种事件类型一个 record，字段都是已脱敏、已限长的安全值；事件类型名由 {@link #type()} 给出，
 * 不另存字段，避免二者不一致。新增类型只能追加。
 */
public sealed interface TurnEventPayload {

    /** 契约里的事件类型名，例如 {@code tool.started}。 */
    String type();

    /** 控制面事件（终态）只能由发布器的 finish 发出。 */
    default boolean terminal() {
        return false;
    }

    /** 一次模型调用的结果。 */
    enum StepOutcome {
        SUCCEEDED,
        FAILED
    }

    /** 工具结束状态，与 tool_invocations.status 一一对应。 */
    enum ToolStatus {
        SUCCESS,
        FAILED,
        TIMEOUT,
        DENIED,
        SKIPPED,
        INTERRUPTED,
        UNCONFIRMED;

        /** 把审计表的状态字符串映射为事件状态；未知值按失败处理。 */
        public static ToolStatus fromAudit(String status) {
            return switch (status == null ? "" : status) {
                case "success" -> SUCCESS;
                case "timeout" -> TIMEOUT;
                case "sandbox_denied" -> DENIED;
                case "limit_skipped" -> SKIPPED;
                case "interrupted" -> INTERRUPTED;
                case "unconfirmed" -> UNCONFIRMED;
                default -> FAILED;
            };
        }
    }

    /** 用户对确认请求的决定。 */
    enum ApprovalDecisionKind {
        ALLOW_ONCE,
        ALLOW_SESSION,
        DENY,
        TIMED_OUT,
        UNATTENDED,
        CANCELLED;

        public static ApprovalDecisionKind from(ApprovalDecision.Outcome outcome) {
            return switch (outcome) {
                case ALLOW_ONCE -> ALLOW_ONCE;
                case ALLOW_SESSION -> ALLOW_SESSION;
                case DENY -> DENY;
                case TIMED_OUT -> TIMED_OUT;
                case UNSUPPORTED -> UNATTENDED;
                case CANCELLED, EOF -> CANCELLED;
            };
        }
    }

    /** 提问的结果。 */
    enum InputOutcome {
        ANSWERED,
        TIMED_OUT,
        UNATTENDED,
        CANCELLED,
        TOO_LONG;

        public static InputOutcome from(InteractionResult.Status status) {
            return switch (status) {
                case ANSWERED -> ANSWERED;
                case TIMED_OUT -> TIMED_OUT;
                case UNSUPPORTED -> UNATTENDED;
                case TOO_LONG -> TOO_LONG;
                case EOF, BUSY, CANCELLED -> CANCELLED;
            };
        }
    }

    /** 轮次开始。 */
    record RunStarted(String agent, String trigger) implements TurnEventPayload {
        public String type() {
            return "run.started";
        }
    }

    /** 一次模型调用开始，iteration 从 1 起。 */
    record StepStarted(int iteration) implements TurnEventPayload {
        public String type() {
            return "step.started";
        }
    }

    /** 一次模型调用结束；服务端没返回的字段为 null。 */
    record StepFinished(
            int iteration,
            StepOutcome outcome,
            String finishReason,
            String requestedModel,
            String actualModel,
            Integer promptTokens,
            Integer completionTokens,
            FailureKind failureKind)
            implements TurnEventPayload {
        public StepFinished {
            Objects.requireNonNull(outcome, "outcome");
        }

        public String type() {
            return "step.finished";
        }
    }

    /** 一条助手消息开始；同一次模型调用的文字属于同一条消息。 */
    record MessageStarted(String messageId, int iteration, String role)
            implements TurnEventPayload {
        public MessageStarted {
            Objects.requireNonNull(messageId, "messageId");
            role = role == null ? "assistant" : role;
        }

        public String type() {
            return "message.started";
        }
    }

    /** 文字增量，非空；可以包含一个或多个字符，不对应 token。 */
    record MessageDelta(String messageId, String text) implements TurnEventPayload {
        public MessageDelta {
            Objects.requireNonNull(messageId, "messageId");
            if (text == null || text.isEmpty()) {
                throw new IllegalArgumentException("文字增量不能为空");
            }
        }

        public String type() {
            return "message.delta";
        }
    }

    /** 一条助手消息结束。 */
    record MessageEnded(String messageId) implements TurnEventPayload {
        public MessageEnded {
            Objects.requireNonNull(messageId, "messageId");
        }

        public String type() {
            return "message.ended";
        }
    }

    /** 单个工具开始执行；参数摘要与审计记录同值，已脱敏限长。 */
    record ToolStarted(
            String toolCallId,
            String toolName,
            String source,
            int iteration,
            String argumentsSummary)
            implements TurnEventPayload {
        public ToolStarted {
            Objects.requireNonNull(toolCallId, "toolCallId");
            Objects.requireNonNull(toolName, "toolName");
        }

        public String type() {
            return "tool.started";
        }
    }

    /** 单个工具结束；结果摘要就是模型看到的那份，失败、拒绝、跳过时可以为 null。 */
    record ToolFinished(
            String toolCallId,
            String toolName,
            ToolStatus status,
            long durationMs,
            boolean truncated,
            String resultSummary,
            boolean possiblyExecuted)
            implements TurnEventPayload {
        public ToolFinished {
            Objects.requireNonNull(toolCallId, "toolCallId");
            Objects.requireNonNull(toolName, "toolName");
            Objects.requireNonNull(status, "status");
        }

        public String type() {
            return "tool.finished";
        }
    }

    /** 请求用户确认一个动作；lines 与 CLI 提示一致。 */
    record ApprovalRequested(
            UUID requestId,
            String toolCallId,
            String actionType,
            String title,
            List<String> lines,
            boolean sessionGrantAllowed)
            implements TurnEventPayload {
        public ApprovalRequested {
            Objects.requireNonNull(requestId, "requestId");
            lines = lines == null ? List.of() : List.copyOf(lines);
        }

        public String type() {
            return "approval.requested";
        }
    }

    /** 确认结果；用户输入的拒绝原因不进入事件。 */
    record ApprovalResolved(UUID requestId, String toolCallId, ApprovalDecisionKind decision)
            implements TurnEventPayload {
        public ApprovalResolved {
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(decision, "decision");
        }

        public String type() {
            return "approval.resolved";
        }
    }

    /** ask_user 向用户提问；问题已脱敏。 */
    record InputRequested(UUID requestId, String toolCallId, String question)
            implements TurnEventPayload {
        public InputRequested {
            Objects.requireNonNull(requestId, "requestId");
        }

        public String type() {
            return "input.requested";
        }
    }

    /** 提问结果；答案正文不进入事件。 */
    record InputResolved(UUID requestId, String toolCallId, InputOutcome outcome)
            implements TurnEventPayload {
        public InputResolved {
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(outcome, "outcome");
        }

        public String type() {
            return "input.resolved";
        }
    }

    /** 轮次成功结束。 */
    record RunCompleted(String text, int iterations, PersistenceStatus persistence)
            implements TurnEventPayload {
        public String type() {
            return "run.completed";
        }

        @Override
        public boolean terminal() {
            return true;
        }
    }

    /** 轮次失败结束；message 是面向用户的固定说明，不含内部细节。 */
    record RunFailed(
            TurnFailureCode code,
            TurnStatus status,
            String message,
            int iterations,
            PersistenceStatus persistence,
            boolean possiblyExecuted)
            implements TurnEventPayload {
        public RunFailed {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(status, "status");
        }

        public String type() {
            return "run.failed";
        }

        @Override
        public boolean terminal() {
            return true;
        }
    }

    /** 用户取消后结束。 */
    record RunCancelled(
            String message, int iterations, PersistenceStatus persistence, boolean possiblyExecuted)
            implements TurnEventPayload {
        public String type() {
            return "run.cancelled";
        }

        @Override
        public boolean terminal() {
            return true;
        }
    }
}
