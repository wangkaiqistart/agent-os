package com.agentos.provider.embedding;

import java.time.Duration;

/**
 * 向量化后台重新校验的退避策略：连续失败第 N 次之后，等多久再校验。
 *
 * <p>做成接口是为了测试能把分钟级的等待缩短到毫秒级，生产环境用 {@link #exponential()}。
 */
@FunctionalInterface
public interface EmbeddingBackoff {

    /** 单次等待的上限（research R12）。 */
    Duration MAX_DELAY = Duration.ofMinutes(30);

    /**
     * @param failures 已经连续失败的次数，从 1 开始
     * @return 下一次校验前要等待的时间
     */
    Duration delay(int failures);

    /** 1 → 2 → 4 … 分钟，最长 30 分钟。 */
    static EmbeddingBackoff exponential() {
        return failures -> {
            // 指数限制在 5 以内，避免位移溢出；32 分钟会被下面的上限截成 30 分钟。
            long minutes = 1L << Math.min(Math.max(failures - 1, 0), 5);
            Duration delay = Duration.ofMinutes(minutes);
            return delay.compareTo(MAX_DELAY) > 0 ? MAX_DELAY : delay;
        };
    }
}
