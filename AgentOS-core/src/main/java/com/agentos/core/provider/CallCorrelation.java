package com.agentos.core.provider;

import java.util.UUID;

/**
 * 把一次 Provider 调用关联到 Session、Turn 和迭代。
 *
 * <p>模型调用本身只知道“发了一个请求”，这些 UUID 让审计记录能回答：
 * 这个请求属于哪个会话、哪一轮对话、哪一次 ReAct 迭代。
 *
 * @param sessionId 会话 ID；没有会话时可以为空
 * @param turnId 当前对话轮次 ID；没有轮次时可以为空
 * @param iteration 当前 ReAct 迭代序号；没有循环上下文时可以为空
 */
public record CallCorrelation(UUID sessionId, UUID turnId, Integer iteration) {

    /** 创建没有业务关联标识的调用关联。 */
    public static CallCorrelation none() {
        // 允许 CLI 的独立调用或早期测试没有 Session/Turn 时仍能构造请求。
        return new CallCorrelation(null, null, null);
    }
}
