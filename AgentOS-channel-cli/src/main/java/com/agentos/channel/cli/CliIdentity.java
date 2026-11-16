package com.agentos.channel.cli;

import com.agentos.core.session.SessionKey;

/**
 * CLI 会话身份值对象：固定渠道 cli、用户标识和 Agent 名称共同组成自然键。
 *
 * @param userId 本机用户标识；不是经过服务端认证的身份
 * @param agentName Agent 目录名，不是 Spring profile
 */
public record CliIdentity(String userId, String agentName) {
    public SessionKey sessionKey() {
        return new SessionKey("cli", userId, agentName);
    }

    /** 从 JVM 系统属性读取本机用户名，缺省用 unknown；不发起登录或权限验证。 */
    public static CliIdentity local(String agentName) {
        return new CliIdentity(System.getProperty("user.name", "unknown"), agentName);
    }
}
