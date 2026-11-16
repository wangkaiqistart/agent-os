package com.agentos.core.turn;

/** 内部上下文键集中定义；不是发送给模型的字段。 */
public final class ToolContextKeys {
    public static final String TURN_CONTEXT = "agentos.turnContext";
    public static final String TOOL_CALL_LEDGER = "agentos.toolCallLedger";
    public static final String TURN_BUDGET = "agentos.turnBudget";

    private ToolContextKeys() {}
}
