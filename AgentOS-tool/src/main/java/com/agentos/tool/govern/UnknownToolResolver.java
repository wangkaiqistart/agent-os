package com.agentos.tool.govern;

import com.agentos.core.audit.ToolInvocationRecorder;
import com.agentos.core.tool.Redactor;
import com.agentos.core.tool.ToolPolicy;
import java.time.Duration;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;

/**
 * 全局兜底只能拒绝：绝不查询 ApplicationContext，也不解析同名 Function/Tool Bean。
 * 请求内的已授权 callbacks 由官方 Manager 优先选择；此处的拒绝同样被治理及审计。
 */
public final class UnknownToolResolver implements ToolCallbackResolver {
    private final ToolInvocationRecorder recorder;
    private final Redactor redactor;
    private final java.util.function.ToIntFunction<String> perToolLimit;
    private final int totalLimit;

    public UnknownToolResolver(ToolInvocationRecorder recorder, Redactor redactor) {
        this(recorder, redactor, name -> 10, 30);
    }

    public UnknownToolResolver(
            ToolInvocationRecorder recorder,
            Redactor redactor,
            java.util.function.ToIntFunction<String> perToolLimit,
            int totalLimit) {
        this.perToolLimit = perToolLimit;
        this.totalLimit = totalLimit;
        this.recorder = recorder;
        this.redactor = redactor;
    }

    public ToolCallback resolve(String name) {
        ToolCallback denied =
                new ToolCallback() {
                    public ToolDefinition getToolDefinition() {
                        return ToolDefinition.builder()
                                .name(name)
                                .description("未授权工具")
                                .inputSchema("{\"type\":\"object\"}")
                                .build();
                    }

                    public String call(String input) {
                        throw new IllegalArgumentException(
                                redactor.redact("本 Agent 没有名为 " + name + " 的工具"));
                    }
                };
        return new GovernedToolCallback(
                denied,
                new ToolPolicy(false, Duration.ofSeconds(30), 10000),
                recorder,
                redactor,
                perToolLimit.applyAsInt(name),
                totalLimit,
                "unknown");
    }
}
