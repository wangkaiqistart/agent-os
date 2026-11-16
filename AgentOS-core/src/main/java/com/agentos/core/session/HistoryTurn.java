package com.agentos.core.session;

import java.util.List;
import java.util.UUID;
import org.springframework.ai.chat.messages.Message;

/**
 * 按轮次组织的历史消息契约，为后续整轮截断、失败轮次修复预留边界。
 *
 * <p>messages 复制为不可变列表，避免历史被调用方继续增删；失败轮次的安全降级由
 * PromptBuilder 根据 status 决定，不修改数据库中的原始消息。
 */
public record HistoryTurn(UUID turnId, String status, String errorMessage, List<Message> messages) {
    public HistoryTurn {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }

    /** 保留轮次身份和状态，替换消息集合，返回新快照。 */
    public HistoryTurn withMessages(List<Message> replacement) {
        return new HistoryTurn(turnId, status, errorMessage, replacement);
    }
}
