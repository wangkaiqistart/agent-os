package com.agentos.core.tool;

import java.time.Instant;
import java.util.List;

/** 不含callback、schema、地址、环境或凭证的目录DTO。 */
public record ToolCatalogView(
        String version, Instant capturedAt, String agent, List<Item> tools, List<Source> sources) {
    public record Item(
            String name,
            String description,
            String source,
            String status,
            Boolean authorized,
            Boolean visible) {}

    public record Source(String source, String status, String reason) {}

    public ToolCatalogView {
        tools = List.copyOf(tools);
        sources = List.copyOf(sources);
    }
}
