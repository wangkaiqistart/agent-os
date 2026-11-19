package com.agentos.core.tool;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.ai.tool.ToolCallback;

/** 一次目录读取及本轮授权的固定绑定，不在迭代间更新。 */
public record ToolCatalogSnapshot(
        String version,
        Instant capturedAt,
        List<ToolDescriptor> descriptors,
        List<ToolSourceDescriptor> sources,
        String agentName,
        List<String> declaredTools,
        List<String> declaredServers,
        List<ToolCallback> modelCallbacks,
        List<ToolCallback> executionCallbacks,
        Map<String, ToolDescriptor> bindingByName) {
    public ToolCatalogSnapshot {
        java.util.Objects.requireNonNull(version);
        java.util.Objects.requireNonNull(capturedAt);
        descriptors = List.copyOf(descriptors);
        sources = List.copyOf(sources);
        declaredTools = List.copyOf(declaredTools);
        declaredServers = List.copyOf(declaredServers);
        modelCallbacks = List.copyOf(modelCallbacks);
        executionCallbacks = List.copyOf(executionCallbacks);
        bindingByName =
                java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(bindingByName));
    }

    public String sourceOf(String name) {
        var descriptor = bindingByName.get(name);
        return descriptor == null ? "unknown" : descriptor.source();
    }
}
