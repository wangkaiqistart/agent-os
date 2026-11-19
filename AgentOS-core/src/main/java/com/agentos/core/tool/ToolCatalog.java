package com.agentos.core.tool;

import com.agentos.core.agent.AgentProfile;
import java.util.List;
import org.springframework.ai.tool.ToolCallback;

/**
 * 根据 Agent 配置取得其允许使用的官方 ToolCallback，Core 不依赖具体工具实现。
 * 实现由 tool 模块提供；ReActLoop 只接收名单内的治理 callback。
 */
public interface ToolCatalog {
    /** 一次取得定义、授权和执行绑定；主链在建轮次前调用。 */
    default ToolCatalogSnapshot resolve(AgentProfile profile) {
        // 兼容旧的静态目录实现；动态目录必须覆写，原子地读取一份注册状态。
        validate(profile);
        var callbacks = List.copyOf(callbacks(profile));
        var descriptors =
                callbacks.stream()
                        .map(callback -> {
                            var definition = callback.getToolDefinition();
                            return new ToolDescriptor(
                                    definition.name(),
                                    definition.description(),
                                    definition.inputSchema(),
                                    "builtin",
                                    null,
                                    new ToolPolicy(false, java.time.Duration.ofSeconds(30), 10000),
                                    callback.getToolMetadata(),
                                    callback,
                                    callback,
                                    ToolDescriptor.Availability.AVAILABLE,
                                    0);
                        })
                        .toList();
        var bindings = new java.util.LinkedHashMap<String, ToolDescriptor>();
        descriptors.forEach(descriptor -> bindings.put(descriptor.name(), descriptor));
        return new ToolCatalogSnapshot(
                "static",
                java.time.Instant.now(),
                descriptors,
                List.of(),
                profile.name(),
                profile.tools(),
                List.of(),
                callbacks,
                callbacks,
                bindings);
    }

    /** 建轮次和模型外呼前的校验入口：检查 Agent 声明的工具是否存在、可用。 */
    void validate(AgentProfile profile);

    /** 返回该 Agent 可暴露给模型的回调集合；不能把全局所有工具无差别返回。 */
    List<ToolCallback> callbacks(AgentProfile profile);
}
