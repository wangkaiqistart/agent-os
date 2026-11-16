package com.agentos.core.tool;

import java.time.Duration;

/**
 * 单个工具的治理参数契约；这里只校验值，不负责执行、重试或截断。
 *
 * @param idempotent 重复调用是否可安全重试；false 的工具不能因为网络失败就自动重发
 * @param timeout 单次工具执行的时间预算，应由具体 I/O 超时配合保证
 * @param maxResultChars 返回结果字符上限配置，执行层需明确处理零值等边界
 */
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
