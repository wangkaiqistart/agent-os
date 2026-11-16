package com.agentos.core.session;

/**
 * 用业务身份定位活跃会话的自然键，与数据库 UUID 主键用途不同。
 *
 * <p>例如 debug-http + debug-user + answer 复用一个活跃会话；切换用户或 Agent 会定位到另一会话。
 * 数据库用 active 状态下的部分唯一索引保证该组合唯一，归档后可以新建同身份会话。
 */
public record SessionKey(String channel, String userId, String profileName) {
    public SessionKey {
        if (channel == null
                || channel.isBlank()
                || userId == null
                || userId.isBlank()
                || profileName == null
                || profileName.isBlank()) {
            throw new IllegalArgumentException("channel、userId、profileName 不能为空");
        }
        channel = channel.trim();
        userId = userId.trim();
        profileName = profileName.trim();
    }
}
