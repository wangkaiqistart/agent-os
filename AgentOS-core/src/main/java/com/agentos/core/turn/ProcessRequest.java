package com.agentos.core.turn;

/**
 * 一次用户输入的统一参数，由 HTTP、CLI 等入口适配后传给 AgentService。
 *
 * @param channel 渠道名称，例如 cli、debug-http，参与会话身份划分
 * @param userId 渠道内用户标识，与 channel、agentName 共同定位活跃会话
 * @param agentName 工作区 agents 下的目录名，不是模型名
 * @param message 本条用户消息；空白消息由 AgentService 拒绝
 * @param trigger 本轮触发来源，写入轮次记录，空值回退为 channel
 */
public record ProcessRequest(
        String channel, String userId, String agentName, String message, String trigger) {
    public ProcessRequest {
        if (channel == null
                || channel.isBlank()
                || userId == null
                || userId.isBlank()
                || agentName == null
                || agentName.isBlank()) {
            throw new IllegalArgumentException("channel、userId、agentName 不能为空");
        }
        message = message == null ? "" : message;
        trigger = trigger == null || trigger.isBlank() ? channel : trigger;
    }
}
