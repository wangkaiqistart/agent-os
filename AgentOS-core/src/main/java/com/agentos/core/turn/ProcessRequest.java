package com.agentos.core.turn;

/** AgentService 的统一输入，不包含输出回调。 */
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
