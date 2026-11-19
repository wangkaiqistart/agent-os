package com.agentos.tool.registry;

import com.agentos.core.agent.AgentProfile;
import com.agentos.core.tool.ToolCatalog;
import com.agentos.core.tool.ToolCatalogSnapshot;
import com.agentos.core.tool.ToolDescriptor;
import com.agentos.core.tool.ToolPolicy;
import com.agentos.core.tool.ToolSourceDescriptor;
import com.agentos.core.tool.UnknownAgentToolException;
import com.agentos.tool.govern.GovernedToolCallback;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;

/** 候选目录整体校验后原子发布；每轮只解析一次，不在模型迭代中重新绑定。 */
public final class ToolRegistry implements ToolCatalog {
    private record Directory(
            long version,
            List<ToolDescriptor> descriptors,
            List<ToolSourceDescriptor> sources,
            Map<String, ToolDescriptor> bindings) {}

    private final AtomicReference<Directory> directory = new AtomicReference<>();
    private final Object publicationLock = new Object();
    private final ToolDefinitionValidator validator = new ToolDefinitionValidator();

    public static ToolRegistry fromDescriptors(List<ToolDescriptor> descriptors) {
        var registry = new ToolRegistry(List.of());
        registry.publish(descriptors, List.of());
        return registry;
    }

    public ToolRegistry(List<ToolCallback> callbacks) {
        if (callbacks == null) {
            throw new IllegalArgumentException("工具目录不能为空");
        }
        publish(
                callbacks.stream()
                        .map(callback -> {
                            if (callback instanceof GovernedToolCallback governed) {
                                return governed.descriptor();
                            }
                            if (callback == null || callback.getToolDefinition() == null) {
                                throw new IllegalArgumentException("工具定义不能为空");
                            }
                            var definition = callback.getToolDefinition();
                            return new ToolDescriptor(
                                    definition.name(),
                                    definition.description(),
                                    definition.inputSchema(),
                                    "builtin",
                                    null,
                                    new ToolPolicy(false, Duration.ofSeconds(30), 10000),
                                    callback.getToolMetadata(),
                                    callback,
                                    callback,
                                    ToolDescriptor.Availability.AVAILABLE,
                                    0);
                        })
                        .toList(),
                List.of());
    }

    /** 失败不改变旧目录；来源维护端只在构造完整候选后调用。 */
    public void publish(List<ToolDescriptor> descriptors, List<ToolSourceDescriptor> sources) {
        var bindings = new LinkedHashMap<String, ToolDescriptor>();
        for (var descriptor : List.copyOf(descriptors)) {
            validator.validateSchema(descriptor.inputSchema());
            if (bindings.putIfAbsent(descriptor.name(), descriptor) != null) {
                throw new IllegalStateException("工具名称重复：" + descriptor.name());
            }
        }
        ToolCallingChatOptions.validateToolCallbacks(
                descriptors.stream().map(ToolDescriptor::callback).toList());
        synchronized (publicationLock) {
            var old = directory.get();
            directory.set(
                    new Directory(
                            old == null ? 1 : old.version() + 1,
                            List.copyOf(descriptors),
                            List.copyOf(sources),
                            java.util.Collections.unmodifiableMap(bindings)));
        }
    }

    @Override
    public ToolCatalogSnapshot resolve(AgentProfile profile) {
        var captured = directory.get();
        var sources = new LinkedHashMap<String, ToolSourceDescriptor>();
        captured.sources().forEach(source -> sources.put(source.source(), source));
        for (String server : profile.mcpServers()) {
            if (!sources.containsKey("mcp:" + server)) {
                throw new UnknownAgentToolException(profile.name(), "mcp_" + server);
            }
        }
        var bindings = new LinkedHashMap<>(captured.bindings());
        var authorized = new java.util.ArrayList<ToolDescriptor>();
        for (String name : profile.tools()) {
            String server = null;
            if (name.startsWith("mcp_")) {
                int separator = name.indexOf("__", 4);
                if (separator > 4) {
                    server = name.substring(4, separator);
                }
                if (server == null
                        || !profile.mcpServers().contains(server)
                        || !sources.containsKey("mcp:" + server)) {
                    throw new UnknownAgentToolException(profile.name(), name);
                }
            }
            ToolDescriptor descriptor = bindings.get(name);
            if (descriptor == null) {
                if (server == null
                        || sources.get("mcp:" + server).state()
                                == ToolSourceDescriptor.State.AVAILABLE) {
                    throw new UnknownAgentToolException(profile.name(), name);
                }
                descriptor = unavailable(name, server);
                bindings.put(name, descriptor);
            }
            authorized.add(descriptor);
        }
        return new ToolCatalogSnapshot(
                Long.toString(captured.version()),
                Instant.now(),
                captured.descriptors(),
                captured.sources(),
                profile.name(),
                profile.tools(),
                profile.mcpServers(),
                authorized.stream()
                        .filter(d -> d.availability() == ToolDescriptor.Availability.AVAILABLE)
                        .map(ToolDescriptor::callback)
                        .toList(),
                authorized.stream().map(ToolDescriptor::callback).toList(),
                bindings);
    }

    /** 无Agent的同一次原子目录读取；查询不触发发现或执行。 */
    public ToolCatalogSnapshot snapshot() {
        var captured = directory.get();
        return new ToolCatalogSnapshot(
                Long.toString(captured.version()),
                Instant.now(),
                captured.descriptors(),
                captured.sources(),
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                captured.bindings());
    }

    public List<ToolDescriptor> descriptors() {
        return directory.get().descriptors();
    }

    private volatile java.util.function.Function<ToolDescriptor, ToolDescriptor>
            unavailableGovernor = java.util.function.Function.identity();

    public void unavailableGovernor(
            java.util.function.Function<ToolDescriptor, ToolDescriptor> factory) {
        unavailableGovernor = factory;
    }

    private ToolDescriptor unavailable(String name, String server) {
        var callback =
                new org.springframework.ai.tool.ToolCallback() {
                    public org.springframework.ai.tool.definition.ToolDefinition
                            getToolDefinition() {
                        return org.springframework.ai.tool.definition.ToolDefinition.builder()
                                .name(name)
                                .description("外部MCP工具暂不可用")
                                .inputSchema("{\"type\":\"object\"}")
                                .build();
                    }

                    public String call(String input) {
                        throw new IllegalStateException("MCP来源不可用");
                    }
                };
        return unavailableGovernor.apply(
                new ToolDescriptor(
                        name,
                        "外部MCP工具暂不可用",
                        "{\"type\":\"object\"}",
                        "mcp:" + server,
                        null,
                        new ToolPolicy(false, Duration.ofSeconds(30), 10000),
                        callback.getToolMetadata(),
                        callback,
                        callback,
                        ToolDescriptor.Availability.UNAVAILABLE,
                        0));
    }

    public void validate(AgentProfile profile) {
        resolve(profile);
    }

    public List<ToolCallback> callbacks(AgentProfile profile) {
        return resolve(profile).modelCallbacks();
    }
}
