package com.agentos.tool.mcp;

/** 固定分类异常，不携带远程内容、地址或配置原值。 */
public final class McpToolException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public McpToolException(String category) {
        super(category);
    }
}
