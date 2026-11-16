package com.agentos.core.turn;

import com.agentos.core.error.AgentOSException;
import com.agentos.core.error.ErrorCode;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;

/**
 * 每批响应独有的调用账本：官方 Manager 按顺序调用，同名工具从队首取模型提供的 ID。
 * 不按参数/输出猜 ID。checkpoint 闭包持有当前轮次快照，供治理层和 HTTP 每跳显式续租。
 */
public final class ToolCallLedger {
    private final List<ToolCall> calls;
    private final Map<String, ArrayDeque<ToolCall>> queues = new LinkedHashMap<>();
    private final Set<String> taken = new HashSet<>();
    private final Runnable checkpoint;

    public ToolCallLedger(List<ToolCall> calls, Runnable checkpoint) {
        validate(calls);
        this.calls = List.copyOf(calls);
        this.checkpoint = Objects.requireNonNull(checkpoint);
        for (ToolCall call : calls) {
            queues.computeIfAbsent(call.name(), key -> new ArrayDeque<>()).addLast(call);
        }
    }

    public ToolCall next(String name) {
        ArrayDeque<ToolCall> queue = queues.get(name);
        if (queue == null || queue.isEmpty()) {
            throw new IllegalStateException("工具调用账本已耗尽");
        }
        ToolCall call = queue.removeFirst();
        taken.add(call.id());
        return call;
    }

    /** 只有 Manager 正常返回后，剩余项才可能代表官方次数限制跳过的调用。 */
    public List<ToolCall> pending() {
        return calls.stream().filter(call -> !taken.contains(call.id())).toList();
    }

    public void checkpoint() {
        checkpoint.run();
    }

    public static void validate(List<ToolCall> calls) {
        Set<String> ids = new HashSet<>();
        for (ToolCall call : calls) {
            if (call.id() == null || call.id().isBlank() || !ids.add(call.id())) {
                throw new AgentOSException(ErrorCode.INVALID_ARGUMENT, "模型工具调用 ID 缺失或重复");
            }
        }
    }
}
