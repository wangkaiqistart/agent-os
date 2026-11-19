package com.agentos.core.tool;

/** 安全只读投影，不发现或执行工具。 */
@FunctionalInterface
public interface ToolCatalogQuery {
    ToolCatalogView snapshot(String agentName);
}
