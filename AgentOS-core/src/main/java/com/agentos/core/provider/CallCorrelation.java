package com.agentos.core.provider;

import java.util.UUID;

/** 把一次 Provider 调用关联到 Session、Turn 和迭代。 */
public record CallCorrelation(UUID sessionId, UUID turnId, Integer iteration) {

    /** 创建没有业务关联标识的调用关联。 */
    public static CallCorrelation none() {
        return new CallCorrelation(null, null, null);
    }
}
