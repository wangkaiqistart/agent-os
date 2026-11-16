package com.agentos.core.session;

import java.util.List;
import org.springframework.ai.chat.messages.Message;

/** 追加消息和读取历史的端口。 */
public interface MessageStore {
    /** 在短事务中追加本轮某次迭代的消息；系统 Prompt 不通过这个端口写入消息库。 */
    void append(TurnSnapshot turn, int iteration, List<Message> messages);

    /** 读取最近若干完整轮次；实现不得对消息行直接截断，也应排除当前 RUNNING 轮次。 */
    List<HistoryTurn> recentTurns(SessionSnapshot session, int maxTurns);
}
