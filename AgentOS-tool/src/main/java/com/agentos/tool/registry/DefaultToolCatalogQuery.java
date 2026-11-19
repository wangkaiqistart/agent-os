package com.agentos.tool.registry;

import com.agentos.core.agent.AgentLoader;
import com.agentos.core.error.AgentOSException;
import com.agentos.core.error.ErrorCode;
import com.agentos.core.tool.Redactor;
import com.agentos.core.tool.ToolCatalogQuery;
import com.agentos.core.tool.ToolCatalogView;
import com.agentos.core.tool.ToolDescriptor;
import java.util.Comparator;

/** 一次读取后仅投影安全字段；不连接来源、不执行工具。 */
public final class DefaultToolCatalogQuery implements ToolCatalogQuery {
    private final ToolRegistry registry;
    private final AgentLoader loader;
    private final Redactor redactor;

    public DefaultToolCatalogQuery(ToolRegistry registry, AgentLoader loader, Redactor redactor) {
        this.registry = registry;
        this.loader = loader;
        this.redactor = redactor;
    }

    public ToolCatalogView snapshot(String agentName) {
        if (agentName != null && (agentName.isBlank() || agentName.length() > 128)) {
            throw new AgentOSException(ErrorCode.INVALID_ARGUMENT, "Agent参数不合法");
        }
        com.agentos.core.tool.ToolCatalogSnapshot snapshot;
        try {
            snapshot =
                    agentName == null
                            ? registry.snapshot()
                            : registry.resolve(loader.load(agentName).profile());
        } catch (AgentOSException invalid) {
            throw new AgentOSException(
                    invalid.getErrorCode(),
                    invalid.getErrorCode() == ErrorCode.NOT_FOUND ? "Agent不存在" : "Agent配置不合法");
        }
        var descriptors =
                agentName == null
                        ? snapshot.descriptors()
                        : snapshot.declaredTools().stream()
                                .map(snapshot.bindingByName()::get)
                                .toList();
        var tools =
                descriptors.stream()
                        .sorted(Comparator.comparing(ToolDescriptor::name))
                        .map(d ->
                                new ToolCatalogView.Item(
                                        d.name(),
                                        redactor.summarize(d.description(), 4000),
                                        d.source(),
                                        d.availability().name(),
                                        agentName == null ? null : Boolean.TRUE,
                                        agentName == null
                                                ? null
                                                : Boolean.valueOf(
                                                        d.availability()
                                                                == ToolDescriptor.Availability
                                                                        .AVAILABLE)))
                        .toList();
        var sources =
                snapshot.sources().stream()
                        .sorted(
                                Comparator.comparing(
                                        com.agentos.core.tool.ToolSourceDescriptor::source))
                        .map(s ->
                                new ToolCatalogView.Source(
                                        s.source(),
                                        s.state().name(),
                                        s.reason() == null ? null : s.reason().name()))
                        .toList();
        return new ToolCatalogView(
                snapshot.version(), snapshot.capturedAt(), agentName, tools, sources);
    }
}
