package com.agentos.channel.cli;

import com.agentos.core.session.SessionKey;

/** CLI 会话身份：渠道、登录用户和 Agent 共同组成自然键。 */
public record CliIdentity(String userId, String agentName) {
    public SessionKey sessionKey() {
        return new SessionKey("cli", userId, agentName);
    }

    public static CliIdentity local(String agentName) {
        return new CliIdentity(System.getProperty("user.name", "unknown"), agentName);
    }
}
