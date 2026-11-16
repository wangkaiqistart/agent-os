package com.agentos.core.session;

import java.util.List;
import org.springframework.ai.chat.messages.Message;

/** 追加消息和读取历史的端口。 */
public interface MessageStore {
    /** 在短事务中追加本轮某次迭代的消息；系统 Prompt 不通过这个端口写入消息库。 */
    void append(TurnSnapshot turn, int iteration, List<Message> messages);

    /** 读取最近若干完整分组；当前实现未完成工具账本恢复或 token 截断，调用方不能作此假设。 */
    List<HistoryTurn> recentTurns(SessionSnapshot session, int maxTurns);
}
