package com.agentos.tool.search;

import com.agentos.core.tool.ToolExecutionScope;
import com.agentos.core.turn.ToolContextKeys;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/** 搜索输入与结构化结果均有界；完整空结果与错误分开。 */
public final class WebSearchTools {
    private final SearchProviderConfig config;
    private final SearchProvider provider;

    public WebSearchTools(SearchProviderConfig config, SearchProvider provider) {
        this.config = config;
        this.provider = provider;
    }

    @Tool(name = "web_search", description = "搜索外部资料的标题、链接和摘要，不自动访问命中页面")
    public String web_search(
            @ToolParam(description = "最多1000字符的搜索词") String query,
            @ToolParam(description = "最多10条", required = false) Integer limit,
            ToolContext context) {
        var scope = (ToolExecutionScope) context.getContext().get(ToolContextKeys.TOOL_SCOPE);
        scope.checkpoint();
        int cap = limit == null ? config.maxResults() : limit;
        if (query == null
                || query.isBlank()
                || query.length() > config.maxQueryChars()
                || cap < 1
                || cap > config.maxResults()) {
            throw new IllegalArgumentException("INVALID_ARGUMENT");
        }
        if (!config.configured()) {
            throw new IllegalStateException("UNCONFIGURED");
        }
        var result = provider.search(query, cap, scope);
        scope.checkpoint();
        var items = new java.util.ArrayList<>(result.items());
        boolean truncated = result.truncated();
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        while (true) {
            String value =
                    json.writeValueAsString(new SearchResult(items, truncated, result.complete()));
            if (value.length() <= 10000) {
                return value;
            }
            if (items.isEmpty()) {
                throw new IllegalStateException("FAILED：结果预算不足");
            }
            items.removeLast();
            truncated = true;
        }
    }
}
