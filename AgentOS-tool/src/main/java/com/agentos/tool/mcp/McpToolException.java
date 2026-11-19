package com.agentos.tool.mcp;

/** 分类异常；只有 REMOTE_ERROR 附带已脱敏、限长的远端错误说明，不携带地址或配置原值。 */
public final class McpToolException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public McpToolException(String category) {
        super(category);
    }
}
