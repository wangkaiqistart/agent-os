package com.agentos.core.interaction;

import com.agentos.core.tool.ToolExecutionScope;

/** 入口显式提供能力，工具不自行推断终端。 */
@FunctionalInterface
public interface InteractionPort {
    InteractionResult ask(InteractionRequest request, ToolExecutionScope scope);

    /**
     * 请求用户确认一个需要授权的动作。默认无人值守：立即返回 UNSUPPORTED，不等待任何输入。
     *
     * <p>实现方在 deadline 前阻塞等待决定，并持续调用 scope.checkpoint() 观察取消与租约。
     */
    default ApprovalDecision approve(ApprovalRequest request, ToolExecutionScope scope) {
        return ApprovalDecision.of(ApprovalDecision.Outcome.UNSUPPORTED);
    }

    InteractionPort UNSUPPORTED =
            (request, scope) -> InteractionResult.of(InteractionResult.Status.UNSUPPORTED);
}
