package com.agentos.core.turn;

/** 内部上下文键集中定义；不是发送给模型的字段。 */
public final class ToolContextKeys {
    public static final String TURN_CONTEXT = "agentos.turnContext";
    public static final String TOOL_CALL_LEDGER = "agentos.toolCallLedger";
    public static final String TURN_BUDGET = "agentos.turnBudget";
    public static final String TOOL_SCOPE = "agentos.toolScope";
    public static final String TOOL_SNAPSHOT = "agentos.toolSnapshot";

    public static final String RUNTIME_CAPABILITIES = "agentos.runtimeCapabilities";

    /** 值为本轮的 {@link TurnEventPublisher}；与其他内部键一样不会经 MCP 发给外部服务。 */
    public static final String TURN_EVENTS = "agentos.turnEvents";

    private ToolContextKeys() {}
}
