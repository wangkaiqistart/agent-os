package com.agentos.core.tool;

import java.time.Duration;

public record ToolPolicy(boolean idempotent, Duration timeout, int maxResultChars) {
    public ToolPolicy {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("工具 timeout 必须为正数");
        }
        if (maxResultChars < 0) {
            throw new IllegalArgumentException("maxResultChars 不能为负数");
        }
    }
}
