package com.agentos.tool.search;

/** 提供者实现同一scope下的有界请求。 */
public interface SearchProvider {
    SearchResult search(String query, int limit, com.agentos.core.tool.ToolExecutionScope scope);
}
