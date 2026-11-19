package com.agentos.core.interaction;

import com.agentos.core.tool.ToolExecutionScope;

/** 入口显式提供能力，工具不自行推断终端。 */
@FunctionalInterface
public interface InteractionPort {
    InteractionResult ask(InteractionRequest request, ToolExecutionScope scope);

    InteractionPort UNSUPPORTED =
            (request, scope) -> InteractionResult.of(InteractionResult.Status.UNSUPPORTED);
}
