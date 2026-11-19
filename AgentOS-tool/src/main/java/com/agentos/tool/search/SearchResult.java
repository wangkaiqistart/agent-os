package com.agentos.tool.search;

import java.util.List;

/** 仅返回资料引用，不访问结果页面。 */
public record SearchResult(List<Item> items, boolean truncated, boolean complete) {
    public record Item(String title, String url, String snippet) {}

    public SearchResult {
        items = List.copyOf(items);
    }
}
