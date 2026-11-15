package com.agentos.core.session;

/** 活跃会话的自然键。 */
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
